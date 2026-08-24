package de.lhns.fs2.compress

import cats.effect.{Async, Deferred, Resource}
import cats.syntax.functor._
import de.lhns.fs2.compress.ArchiveEntry.{ArchiveEntryFromUnderlying, ArchiveEntryToUnderlying}
import de.lhns.fs2.compress.Archiver.checkUncompressedSize
import de.lhns.fs2.compress.Zip._
import fs2.io._
import fs2.{Pipe, Stream}

import java.io.{BufferedInputStream, InputStream, OutputStream}
import java.nio.file.attribute.FileTime
import java.util.zip.{ZipEntry, ZipInputStream, ZipOutputStream}

object Zip {
  // The underlying information is lost if the name or isDirectory attribute of an ArchiveEntry is changed
  implicit val zipArchiveEntryToUnderlying: ArchiveEntryToUnderlying[ZipEntry] =
    new ArchiveEntryToUnderlying[ZipEntry] {
      override def underlying[S[A] <: Option[A]](entry: ArchiveEntry[S, Any], underlying: Any): ZipEntry = {
        val zipEntry = underlying match {
          case zipEntry: ZipEntry if zipEntry.getName == entry.name && zipEntry.isDirectory == entry.isDirectory =>
            new ZipEntry(zipEntry)

          case _ =>
            val fileOrDirName = entry.name match {
              case name if entry.isDirectory && !name.endsWith("/") => name + "/"
              case name if !entry.isDirectory && name.endsWith("/") => name.dropRight(1)
              case name => name
            }
            new ZipEntry(fileOrDirName)
        }

        entry.uncompressedSize.foreach(zipEntry.setSize)
        entry.lastModified.map(FileTime.from).foreach(zipEntry.setLastModifiedTime)
        entry.lastAccess.map(FileTime.from).foreach(zipEntry.setLastAccessTime)
        entry.creation.map(FileTime.from).foreach(zipEntry.setCreationTime)
        zipEntry
      }
    }

  implicit val zipArchiveEntryFromUnderlying: ArchiveEntryFromUnderlying[Option, ZipEntry] =
    new ArchiveEntryFromUnderlying[Option, ZipEntry] {
      override def archiveEntry(underlying: ZipEntry): ArchiveEntry[Option, ZipEntry] =
        ArchiveEntry(
          name = underlying.getName,
          isDirectory = underlying.isDirectory,
          uncompressedSize = Some(underlying.getSize).filterNot(_ == -1),
          lastModified = Option(underlying.getLastModifiedTime).map(_.toInstant),
          lastAccess = Option(underlying.getLastAccessTime).map(_.toInstant),
          creation = Option(underlying.getCreationTime).map(_.toInstant),
          underlying = underlying
        )
    }
}

class ZipArchiver[F[_]: Async, Size[A] <: Option[A]] private (method: Int, chunkSize: Int) extends Archiver[F, Size] {
  override def archive: Pipe[F, (ArchiveEntry[Size, Any], Stream[F, Byte]), Byte] = { stream =>
    readOutputStream[F](chunkSize) { outputStream =>
      Resource
        .make(Async[F].delay {
          val zipOutputStream = new ZipOutputStream(outputStream)
          zipOutputStream.setMethod(method)
          zipOutputStream
        })(os =>
          // Safety net for the paths where the stream above did not get to close `os` itself:
          // cancellation, or an error. Closing the pipe first is what makes this non-blocking - writes
          // to a closed PipedStreamBuffer are no-ops rather than errors, so `close()` runs to
          // completion and still frees what it owns, instead of blocking forever on a consumer that
          // stopped draining (#113). Any genuine close error has already surfaced from the stream.
          Async[F].void(Async[F].attempt(Async[F].blocking {
            outputStream.close()
            os.close()
          }))
        )
        .use { zipOutputStream =>
          (stream
            .through(checkUncompressedSize)
            .flatMap { case (archiveEntry, stream) =>
              def entry = archiveEntry.underlying[ZipEntry]

              // The entry header and trailer are written inside the stream rather than through a
              // Resource. Resource acquire and release are both uncancelable, so writing them there
              // blocks forever once the consumer stops draining the readOutputStream pipe. Here they
              // sit in a cancelable region, so an interrupted write aborts and lets the enclosing
              // finalizer close the pipe. On the happy path the byte order is unchanged.
              Stream.exec(Async[F].interruptible(zipOutputStream.putNextEntry(entry))) ++
                stream
                  .through(writeOutputStream(Async[F].pure[OutputStream](zipOutputStream), closeAfterUse = false)) ++
                Stream.exec(Async[F].interruptible(zipOutputStream.closeEntry()))
            } ++
            // Closing the codec here rather than in the resource finalizer is deliberate: this is a
            // cancelable region, so an interrupted close aborts and lets the finalizer close the
            // pipe. In a finalizer the same call is uninterruptible and blocks forever whenever the
            // consumer has stopped draining (#113).
            Stream.exec(Async[F].interruptible(zipOutputStream.close()))).compile.drain
        }
    }
  }
}

object ZipArchiver {
  def apply[F[_], Size[A] <: Option[A]](implicit instance: ZipArchiver[F, Size]): ZipArchiver[F, Size] = instance

  @deprecated("Use makeDeflated or makeStored instead", "2.2")
  def make[F[_]: Async, Size[A] <: Option[A]](
      method: Int = ZipOutputStream.DEFLATED,
      chunkSize: Int = Defaults.defaultChunkSize
  ): ZipArchiver[F, Size] =
    new ZipArchiver(method, chunkSize)

  /** Make a new [[ZipArchiver]] which uses the DEFLATED method for entries that don't specify their own method.
    */
  def makeDeflated[F[_]: Async](chunkSize: Int = Defaults.defaultChunkSize): ZipArchiver[F, Option] =
    make[F, Option](ZipOutputStream.DEFLATED, chunkSize)

  /** Make a new [[ZipArchiver]] which uses the STORED method for entries that don't specify their own method.
    * @note
    *   In order to use the STORED method the size must be known up front.
    */
  def makeStored[F[_]: Async](chunkSize: Int = Defaults.defaultChunkSize): ZipArchiver[F, Some] =
    make[F, Some](ZipOutputStream.STORED, chunkSize)
}

class ZipUnarchiver[F[_]: Async] private (chunkSize: Int) extends Unarchiver[F, Option, ZipEntry] {
  override def unarchive: Pipe[F, Byte, (ArchiveEntry[Option, ZipEntry], Stream[F, Byte])] = { stream =>
    stream
      .through(toInputStream[F])
      .map(new BufferedInputStream(_, chunkSize))
      .flatMap { inputStream =>
        Stream.resource(
          Resource.make(
            Async[F].blocking(new ZipInputStream(inputStream))
          )(s => Async[F].blocking(s.close()))
        )
      }
      .flatMap { zipInputStream =>
        def readEntries: Stream[F, (ArchiveEntry[Option, ZipEntry], Stream[F, Byte])] =
          Stream
            // Deliberately no closeEntry() finalizer: ZipInputStream.getNextEntry() calls
            // closeEntry() itself before advancing, so it was redundant. Because finalizers run
            // uninterruptibly it drained the whole remaining entry from the still live upstream on
            // cancellation, which is why the stream could not be cancelled (#113). This matches
            // TarUnarchiver, which never had one, and Zip4JUnarchiver, where it is commented out.
            .eval(Async[F].blocking(Option(zipInputStream.getNextEntry)))
            .flatMap(Stream.fromOption[F](_))
            .flatMap { entry =>
              val archiveEntry = ArchiveEntry.fromUnderlying(entry)

              Stream
                .eval(Deferred[F, Unit])
                .flatMap { deferred =>
                  Stream.emit(
                    readInputStream(Async[F].pure[InputStream](zipInputStream), chunkSize, closeAfterUse = false) ++
                      Stream.exec(deferred.complete(()).void)
                  ) ++
                    Stream.exec(deferred.get)
                }
                .map(stream => (archiveEntry, stream)) ++
                readEntries
            }

        readEntries
      }
  }
}

object ZipUnarchiver {
  def apply[F[_]](implicit instance: ZipUnarchiver[F]): ZipUnarchiver[F] = instance

  def make[F[_]: Async](chunkSize: Int = Defaults.defaultChunkSize): ZipUnarchiver[F] =
    new ZipUnarchiver(chunkSize)
}
