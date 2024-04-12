package de.lhns.fs2.compress

import cats.effect.{Async, Deferred, Resource}
import cats.syntax.functor._
import de.lhns.fs2.compress.ArchiveEntry.{ArchiveEntryFromUnderlying, ArchiveEntryToUnderlying}
import de.lhns.fs2.compress.Zip._
import fs2.io._
import fs2.{Pipe, Stream}

import java.io.{BufferedInputStream, InputStream, OutputStream}
import java.nio.file.attribute.FileTime
import java.util.zip.{ZipInputStream, ZipOutputStream, ZipEntry => JZipEntry}

object Zip {
  implicit val zipArchiveEntryToUnderlying: ArchiveEntryToUnderlying[JZipEntry] = new ArchiveEntryToUnderlying[JZipEntry] {
    override def underlying[S[A] <: Option[A]](entry: ArchiveEntry[S, Any], underlying: Any): JZipEntry =
      underlying match {
        case zipEntry: JZipEntry =>
          new JZipEntry(zipEntry)

        case _ =>
          val fileOrDirName = entry.name match {
            case name if entry.isDirectory && !name.endsWith("/") => name + "/"
            case name if !entry.isDirectory && name.endsWith("/") => name.dropRight(1)
            case name => name
          }
          val zipEntry = new JZipEntry(fileOrDirName)
          entry.uncompressedSize.foreach(zipEntry.setSize)
          entry.lastModified.map(FileTime.from).foreach(zipEntry.setLastModifiedTime)
          entry.lastAccess.map(FileTime.from).foreach(zipEntry.setLastAccessTime)
          entry.creation.map(FileTime.from).foreach(zipEntry.setCreationTime)
          zipEntry
      }
  }

  implicit val zipArchiveEntryFromUnderlying: ArchiveEntryFromUnderlying[Option, JZipEntry] = new ArchiveEntryFromUnderlying[Option, JZipEntry] {
    override def archiveEntry(underlying: JZipEntry): ArchiveEntry[Option, JZipEntry] =
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

class ZipArchiver[F[_] : Async](method: Int, chunkSize: Int) extends Archiver[F, Some] {
  def archive: Pipe[F, (ArchiveEntry[Some, Any], Stream[F, Byte]), Byte] = { stream =>
    readOutputStream[F](chunkSize) { outputStream =>
      Resource.make(Async[F].delay {
        val zipOutputStream = new ZipOutputStream(outputStream)
        zipOutputStream.setMethod(method)
        zipOutputStream
      })(zipOutputStream =>
        Async[F].blocking(zipOutputStream.close())
      ).use { zipOutputStream =>
        stream
          .flatMap {
            case (archiveEntry, stream) =>
              def entry = archiveEntry.underlying[JZipEntry]

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

class ZipUnarchiver[F[_] : Async](chunkSize: Int) extends Unarchiver[F, Option, JZipEntry] {
  def unarchive: Pipe[F, Byte, (ArchiveEntry[Option, JZipEntry], Stream[F, Byte])] = { stream =>
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
        def readEntries: Stream[F, (ArchiveEntry[Option, JZipEntry], Stream[F, Byte])] =
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
