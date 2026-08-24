package de.lhns.fs2.compress

import cats.effect.{Async, Deferred, Resource}
import cats.syntax.functor._
import de.lhns.fs2.compress.ArchiveEntry.{ArchiveEntryFromUnderlying, ArchiveEntryToUnderlying}
import de.lhns.fs2.compress.Tar._
import fs2.io._
import fs2.{Pipe, Stream}
import org.apache.commons.compress.archivers.tar.{TarArchiveEntry, TarArchiveInputStream, TarArchiveOutputStream}
import org.apache.commons.compress.archivers.{ArchiveEntry => CommonsArchiveEntry}

import java.io.{BufferedInputStream, InputStream, OutputStream}
import java.nio.file.attribute.FileTime

object Tar {
  // The underlying information is lost if the isDirectory attribute of an ArchiveEntry is changed
  implicit val tarArchiveEntryToUnderlying: ArchiveEntryToUnderlying[TarArchiveEntry] =
    new ArchiveEntryToUnderlying[TarArchiveEntry] {
      override def underlying[S[A] <: Option[A]](entry: ArchiveEntry[S, Any], underlying: Any): TarArchiveEntry = {
        val fileOrDirName = entry.name match {
          case name if entry.isDirectory && !name.endsWith("/") => name + "/"
          case name if !entry.isDirectory && name.endsWith("/") => name.dropRight(1)
          case name => name
        }

        val tarEntry = underlying match {
          case tarEntry: TarArchiveEntry if tarEntry.isDirectory == entry.isDirectory =>
            // copy TarArchiveEntry
            val buffer = new Array[Byte](512) // TarArchiveOutputStream.RECORD_SIZE
            tarEntry.writeEntryHeader(buffer)
            val newTarEntry = new TarArchiveEntry(buffer)
            newTarEntry.setName(fileOrDirName)
            newTarEntry

          case _ =>
            new TarArchiveEntry(fileOrDirName)
        }

        entry.uncompressedSize.foreach(tarEntry.setSize)
        entry.lastModified.map(FileTime.from).foreach(tarEntry.setLastModifiedTime)
        entry.lastAccess.map(FileTime.from).foreach(tarEntry.setLastAccessTime)
        entry.creation.map(FileTime.from).foreach(tarEntry.setCreationTime)
        tarEntry
      }
    }

  implicit val tarArchiveEntryFromUnderlying: ArchiveEntryFromUnderlying[Option, TarArchiveEntry] =
    new ArchiveEntryFromUnderlying[Option, TarArchiveEntry] {
      override def archiveEntry(underlying: TarArchiveEntry): ArchiveEntry[Option, TarArchiveEntry] =
        ArchiveEntry(
          name = underlying.getName,
          isDirectory = underlying.isDirectory,
          uncompressedSize = Some(underlying.getSize).filterNot(_ == CommonsArchiveEntry.SIZE_UNKNOWN),
          lastModified = Option(underlying.getLastModifiedTime).map(_.toInstant),
          lastAccess = Option(underlying.getLastAccessTime).map(_.toInstant),
          creation = Option(underlying.getCreationTime).map(_.toInstant),
          underlying = underlying
        )
    }
}

class TarArchiver[F[_]: Async] private (chunkSize: Int) extends Archiver[F, Some] {
  override def archive: Pipe[F, (ArchiveEntry[Some, Any], Stream[F, Byte]), Byte] = { stream =>
    readOutputStream[F](chunkSize) { outputStream =>
      Resource
        .make(Async[F].delay {
          new TarArchiveOutputStream(outputStream)
        })(OutputStreams.abandoning[F](outputStream))
        .use { tarOutputStream =>
          (stream
            .flatMap { case (archiveEntry, stream) =>
              def entry = archiveEntry.underlying[TarArchiveEntry]

              // The entry header and trailer are written inside the stream rather than through a
              // Resource. Resource acquire and release are both uncancelable, so writing them there
              // blocks forever once the consumer stops draining the readOutputStream pipe. Here they
              // sit in a cancelable region, so an interrupted write aborts and lets the enclosing
              // finalizer close the pipe. On the happy path the byte order is unchanged.
              Stream.exec(Async[F].interruptible(tarOutputStream.putArchiveEntry(entry))) ++
                stream
                  .through(writeOutputStream(Async[F].pure[OutputStream](tarOutputStream), closeAfterUse = false)) ++
                Stream.exec(Async[F].interruptible(tarOutputStream.closeArchiveEntry()))
            } ++
            // Closing the codec here rather than in the resource finalizer is deliberate: this is a
            // cancelable region, so an interrupted close aborts and lets the finalizer close the
            // pipe. In a finalizer the same call is uninterruptible and blocks forever whenever the
            // consumer has stopped draining (#113).
            Stream.exec(Async[F].interruptible(tarOutputStream.close()))).compile.drain
        }
    }
  }
}

object TarArchiver {
  def apply[F[_]](implicit instance: TarArchiver[F]): TarArchiver[F] = instance

  def make[F[_]: Async](chunkSize: Int = Defaults.defaultChunkSize): TarArchiver[F] =
    new TarArchiver(chunkSize)
}

class TarUnarchiver[F[_]: Async] private (chunkSize: Int) extends Unarchiver[F, Option, TarArchiveEntry] {
  override def unarchive: Pipe[F, Byte, (ArchiveEntry[Option, TarArchiveEntry], Stream[F, Byte])] = { stream =>
    stream
      .through(toInputStream[F])
      .map(new BufferedInputStream(_, chunkSize))
      .flatMap { inputStream =>
        Stream.resource(
          Resource.make(
            Async[F].blocking(new TarArchiveInputStream(inputStream))
          )(s => Async[F].blocking(s.close()))
        )
      }
      .flatMap { tarInputStream =>
        def readEntries: Stream[F, (ArchiveEntry[Option, TarArchiveEntry], Stream[F, Byte])] =
          Stream
            .eval(Async[F].blocking(Option(tarInputStream.getNextEntry)))
            .flatMap(Stream.fromOption[F](_))
            .flatMap { entry =>
              val archiveEntry = ArchiveEntry.fromUnderlying(entry)

              Stream
                .eval(Deferred[F, Unit])
                .flatMap { deferred =>
                  Stream.emit(
                    readInputStream(Async[F].pure[InputStream](tarInputStream), chunkSize, closeAfterUse = false) ++
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

object TarUnarchiver {
  def apply[F[_]](implicit instance: TarUnarchiver[F]): TarUnarchiver[F] = instance

  def make[F[_]: Async](chunkSize: Int = Defaults.defaultChunkSize): TarUnarchiver[F] =
    new TarUnarchiver(chunkSize)
}
