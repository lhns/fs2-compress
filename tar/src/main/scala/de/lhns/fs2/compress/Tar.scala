package de.lhns.fs2.compress

import cats.effect.{Async, Deferred, Resource}
import cats.syntax.functor._
import de.lhns.fs2.compress.Tar.TarEntry
import fs2.io._
import fs2.{Pipe, Stream}
import org.apache.commons.compress.archivers.tar.{TarArchiveEntry, TarArchiveInputStream, TarArchiveOutputStream}
import org.apache.commons.compress.archivers.{ArchiveEntry => CommonsArchiveEntry}

import java.io.{BufferedInputStream, InputStream, OutputStream}
import java.nio.file.attribute.FileTime
import java.time.Instant

object Tar {
  trait TarEntry[Size[A] <: Option[A]] extends ArchiveEntry[Size] {
    def underlying: TarArchiveEntry
  }

  object TarEntry {
    def fromTarArchiveEntry(entry: TarArchiveEntry): TarEntry[Option] = new TarEntry[Option] {
      override def underlying: TarArchiveEntry =
        entry

      override def name: String =
        entry.getName

      override def size: Option[Long] =
        Some(entry.getSize).filterNot(_ == CommonsArchiveEntry.SIZE_UNKNOWN)

      override def isDirectory: Boolean =
        entry.isDirectory

      override def lastModified: Option[Instant] =
        Option(entry.getLastModifiedDate).map(_.toInstant)
    }

    def apply[Size[A] <: Option[A]](entry: ArchiveEntry[Size]): TarEntry[Size] = entry match {
      case tarEntry: TarEntry[Size] => tarEntry
      case _ => new TarEntry[Size] {
        override def underlying: TarArchiveEntry = {
          val fileOrDirName = name match {
            case name if isDirectory && !name.endsWith("/") => name + "/"
            case name if !isDirectory && name.endsWith("/") => name.dropRight(1)
            case name => name
          }
          val entry = new TarArchiveEntry(fileOrDirName)
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

class TarArchiver[F[_] : Async](chunkSize: Int) extends Archiver[F, Some] {
  override def archive: Pipe[F, (ArchiveEntry[Some], Stream[F, Byte]), Byte] = { stream =>
    readOutputStream[F](chunkSize) { outputStream =>
      Resource.make(Async[F].delay {
        new TarArchiveOutputStream(outputStream)
      })(s =>
        Async[F].blocking(s.close())
      ).use { tarOutputStream =>
        stream
          .flatMap {
            case (archiveEntry, stream) =>
              def entry = TarEntry(archiveEntry).underlying

              Stream.resource(Resource.make(
                  Async[F].blocking(tarOutputStream.putArchiveEntry(entry))
                )(_ =>
                  Async[F].blocking(tarOutputStream.closeArchiveEntry())
                ))
                .flatMap(_ =>
                  stream
                    .through(writeOutputStream(Async[F].pure[OutputStream](tarOutputStream), closeAfterUse = false))
                )
          }
          .compile
          .drain
      }
    }
  }
}

object TarArchiver {
  def apply[F[_]](implicit instance: TarArchiver[F]): TarArchiver[F] = instance

  def make[F[_] : Async](chunkSize: Int = Defaults.defaultChunkSize): TarArchiver[F] =
    new TarArchiver(chunkSize)
}

class TarUnarchiver[F[_] : Async](chunkSize: Int) extends Unarchiver[F, TarEntry, Option] {
  override def unarchive: Pipe[F, Byte, (TarEntry[Option], Stream[F, Byte])] = { stream =>
    stream
      .through(toInputStream[F]).map(new BufferedInputStream(_, chunkSize))
      .flatMap { inputStream =>
        Stream.resource(Resource.make(
          Async[F].blocking(new TarArchiveInputStream(inputStream))
        )(s =>
          Async[F].blocking(s.close())
        ))
      }
      .flatMap { tarInputStream =>
        def readEntries: Stream[F, (TarEntry[Option], Stream[F, Byte])] =
          Stream.eval(Async[F].blocking(Option(tarInputStream.getNextEntry)))
            .flatMap(Stream.fromOption[F](_))
            .flatMap { entry =>
              val tarEntry = TarEntry.fromTarArchiveEntry(entry)
              Stream.eval(Deferred[F, Unit])
                .flatMap { deferred =>
                  Stream.emit(
                    readInputStream(Async[F].pure[InputStream](tarInputStream), chunkSize, closeAfterUse = false) ++
                      Stream.exec(deferred.complete(()).void)
                  ) ++
                    Stream.exec(deferred.get)
                }
                .map(stream => (tarEntry, stream)) ++
                readEntries
            }

        readEntries
      }
  }
}

object TarUnarchiver {
  def apply[F[_]](implicit instance: TarUnarchiver[F]): TarUnarchiver[F] = instance

  def make[F[_] : Async](chunkSize: Int = Defaults.defaultChunkSize): TarUnarchiver[F] =
    new TarUnarchiver(chunkSize)
}
