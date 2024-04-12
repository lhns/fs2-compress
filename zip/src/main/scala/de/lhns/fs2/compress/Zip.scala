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
  implicit val zipArchiveEntryToUnderlying: ArchiveEntryToUnderlying[ZipEntry] = new ArchiveEntryToUnderlying[ZipEntry] {
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

  implicit val zipArchiveEntryFromUnderlying: ArchiveEntryFromUnderlying[Option, ZipEntry] = new ArchiveEntryFromUnderlying[Option, ZipEntry] {
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

class ZipArchiver[F[_] : Async] private(method: Int,
                                        chunkSize: Int) extends Archiver[F, Some] {
  override def archive: Pipe[F, (ArchiveEntry[Some, Any], Stream[F, Byte]), Byte] = { stream =>
    readOutputStream[F](chunkSize) { outputStream =>
      Resource.make(Async[F].delay {
        val zipOutputStream = new ZipOutputStream(outputStream)
        zipOutputStream.setMethod(method)
        zipOutputStream
      })(zipOutputStream =>
        Async[F].blocking(zipOutputStream.close())
      ).use { zipOutputStream =>
        stream
          .through(checkUncompressedSize)
          .flatMap {
            case (archiveEntry, stream) =>
              def entry = archiveEntry.underlying[ZipEntry]

              Stream.resource(Resource.make(
                  Async[F].blocking(zipOutputStream.putNextEntry(entry))
                )(_ =>
                  Async[F].blocking(zipOutputStream.closeEntry())
                ))
                .flatMap(_ =>
                  stream
                    .through(writeOutputStream(Async[F].pure[OutputStream](zipOutputStream), closeAfterUse = false))
                )
          }
          .compile
          .drain
      }
    }
  }
}

object ZipArchiver {
  def apply[F[_]](implicit instance: ZipArchiver[F]): ZipArchiver[F] = instance

  def make[F[_] : Async](method: Int = ZipOutputStream.DEFLATED,
                         chunkSize: Int = Defaults.defaultChunkSize): ZipArchiver[F] =
    new ZipArchiver(method, chunkSize)
}

class ZipUnarchiver[F[_] : Async] private(chunkSize: Int) extends Unarchiver[F, Option, ZipEntry] {
  override def unarchive: Pipe[F, Byte, (ArchiveEntry[Option, ZipEntry], Stream[F, Byte])] = { stream =>
    stream
      .through(toInputStream[F]).map(new BufferedInputStream(_, chunkSize))
      .flatMap { inputStream =>
        Stream.resource(Resource.make(
          Async[F].blocking(new ZipInputStream(inputStream))
        )(s =>
          Async[F].blocking(s.close())
        ))
      }
      .flatMap { zipInputStream =>
        def readEntries: Stream[F, (ArchiveEntry[Option, ZipEntry], Stream[F, Byte])] =
          Stream.resource(Resource.make(
              Async[F].blocking(Option(zipInputStream.getNextEntry))
            )(_ =>
              Async[F].blocking(zipInputStream.closeEntry())
            ))
            .flatMap(Stream.fromOption[F](_))
            .flatMap { entry =>
              val archiveEntry = ArchiveEntry.fromUnderlying(entry)

              Stream.eval(Deferred[F, Unit])
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

  def make[F[_] : Async](chunkSize: Int = Defaults.defaultChunkSize): ZipUnarchiver[F] =
    new ZipUnarchiver(chunkSize)
}
