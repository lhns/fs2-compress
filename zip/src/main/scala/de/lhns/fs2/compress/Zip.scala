package de.lhns.fs2.compress

import cats.effect.{Async, Deferred, Resource}
import cats.syntax.functor._
import de.lhns.fs2.compress.Zip.ZipEntry
import fs2.io._
import fs2.{Pipe, Stream}

import java.io.{BufferedInputStream, InputStream, OutputStream}
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.zip.{ZipInputStream, ZipOutputStream, ZipEntry => JZipEntry}

object Zip {
  trait ZipEntry[Size[A] <: Option[A]] extends ArchiveEntry[Size] {
    def underlying: JZipEntry
  }

  object ZipEntry {
    def fromJZipEntry(entry: JZipEntry): ZipEntry[Option] = new ZipEntry[Option] {
      override def underlying: JZipEntry =
        new JZipEntry(entry)

      override def name: String =
        entry.getName

      override def size: Option[Long] =
        Some(entry.getSize).filterNot(_ == -1)

      override def isDirectory: Boolean =
        entry.isDirectory

      override def lastModified: Option[Instant] =
        Option(entry.getLastModifiedTime).map(_.toInstant)
    }

    def apply[Size[A] <: Option[A]](entry: ArchiveEntry[Size]): ZipEntry[Size] = entry match {
      case zipEntry: ZipEntry[Size] => zipEntry
      case _ => new ZipEntry[Size] {
        override def underlying: JZipEntry = {
          val fileOrDirName = name match {
            case name if isDirectory && !name.endsWith("/") => name + "/"
            case name if !isDirectory && name.endsWith("/") => name.dropRight(1)
            case name => name
          }
          val entry = new JZipEntry(fileOrDirName)
          size.foreach(entry.setSize)
          lastModified.map(FileTime.from).foreach(entry.setLastModifiedTime)
          entry
        }

        override def name: String = entry.name

        override def size: Size[Long] = entry.size

        override def isDirectory: Boolean = entry.isDirectory

        override def lastModified: Option[Instant] = entry.lastModified
      }
    }
  }
}

class ZipArchiver[F[_] : Async](method: Int, chunkSize: Int) extends Archiver[F, Some] {
  def archive: Pipe[F, (ArchiveEntry[Some], Stream[F, Byte]), Byte] = { stream =>
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
              def entry = ZipEntry(archiveEntry).underlying

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

class ZipUnarchiver[F[_] : Async](chunkSize: Int) extends Unarchiver[F, ZipEntry, Option] {
  def unarchive: Pipe[F, Byte, (ZipEntry[Option], Stream[F, Byte])] = { stream =>
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
        def readEntries: Stream[F, (ZipEntry[Option], Stream[F, Byte])] =
          Stream.resource(Resource.make(
              Async[F].blocking(Option(zipInputStream.getNextEntry))
            )(_ =>
              Async[F].blocking(zipInputStream.closeEntry())
            ))
            .flatMap(Stream.fromOption[F](_))
            .flatMap { entry =>
              val zipEntry = ZipEntry.fromJZipEntry(entry)
              Stream.eval(Deferred[F, Unit])
                .flatMap { deferred =>
                  Stream.emit(
                    readInputStream(Async[F].pure[InputStream](zipInputStream), chunkSize, closeAfterUse = false) ++
                      Stream.exec(deferred.complete(()).void)
                  ) ++
                    Stream.exec(deferred.get)
                }
                .map(stream => (zipEntry, stream)) ++
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
