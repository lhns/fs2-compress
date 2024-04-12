package de.lhns.fs2.compress

import cats.effect.{Async, Deferred, Resource}
import cats.syntax.functor._
import de.lhns.fs2.compress.ArchiveEntry.{ArchiveEntryFromUnderlying, ArchiveEntryToUnderlying}
import de.lhns.fs2.compress.Archiver.checkUncompressedSize
import de.lhns.fs2.compress.Zip4J._
import fs2.io.{readInputStream, readOutputStream, toInputStream, writeOutputStream}
import fs2.{Pipe, Stream}
import net.lingala.zip4j.io.inputstream.ZipInputStream
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.{LocalFileHeader, ZipParameters}

import java.io.{BufferedInputStream, InputStream, OutputStream}
import java.time.Instant

object Zip4J {
  implicit val zip4jArchiveEntryToUnderlying: ArchiveEntryToUnderlying[ZipParameters] = new ArchiveEntryToUnderlying[ZipParameters] {
    override def underlying[S[A] <: Option[A]](entry: ArchiveEntry[S, Any], underlying: Any): ZipParameters = {
      val zipEntry = underlying match {
        case zipParameters: ZipParameters =>
          new ZipParameters(zipParameters)

        case _ =>
          new ZipParameters()
      }

      val fileOrDirName = entry.name match {
        case name if entry.isDirectory && !name.endsWith("/") => name + "/"
        case name if !entry.isDirectory && name.endsWith("/") => name.dropRight(1)
        case name => name
      }

      zipEntry.setFileNameInZip(fileOrDirName)
      entry.uncompressedSize.foreach(zipEntry.setEntrySize)
      entry.lastModified.map(_.toEpochMilli).foreach(zipEntry.setLastModifiedFileTime)
      zipEntry
    }
  }

  implicit val zip4jArchiveEntryFromUnderlying: ArchiveEntryFromUnderlying[Option, ZipParameters] = new ArchiveEntryFromUnderlying[Option, ZipParameters] {
    override def archiveEntry(underlying: ZipParameters): ArchiveEntry[Option, ZipParameters] =
      ArchiveEntry(
        name = underlying.getFileNameInZip,
        isDirectory = underlying.getFileNameInZip.endsWith("/"), //TODO entry.isDirectory
        uncompressedSize = Some(underlying.getEntrySize).filterNot(_ == -1),
        lastModified = Some(underlying.getLastModifiedFileTime).map(Instant.ofEpochMilli),
        underlying = underlying
      )
  }

  implicit val zip4jLocalFileHeaderArchiveEntryFromUnderlying: ArchiveEntryFromUnderlying[Option, LocalFileHeader] = new ArchiveEntryFromUnderlying[Option, LocalFileHeader] {
    override def archiveEntry(underlying: LocalFileHeader): ArchiveEntry[Option, LocalFileHeader] =
      ArchiveEntry(
        name = underlying.getFileName,
        isDirectory = underlying.isDirectory,
        uncompressedSize = Some(underlying.getUncompressedSize).filterNot(_ == -1),
        lastModified = Some(underlying.getLastModifiedTime).map(Instant.ofEpochMilli),
        underlying = underlying
      )
  }
}

class Zip4JArchiver[F[_] : Async](password: => Option[String], chunkSize: Int) extends Archiver[F, Some] {
  def archive: Pipe[F, (ArchiveEntry[Some, Any], Stream[F, Byte]), Byte] = { stream =>
    readOutputStream[F](chunkSize) { outputStream =>
      Resource.make(Async[F].delay {
        val zipOutputStream = new ZipOutputStream(outputStream, password.map(_.toCharArray).orNull)
        zipOutputStream
      })(zipOutputStream =>
        Async[F].blocking(zipOutputStream.close())
      ).use { zipOutputStream =>
        stream
          .through(checkUncompressedSize)
          .flatMap {
            case (archiveEntry, stream) =>
              def entry = archiveEntry.underlying[ZipParameters]

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

object Zip4JArchiver {
  def apply[F[_]](implicit instance: Zip4JArchiver[F]): Zip4JArchiver[F] = instance

  def make[F[_] : Async](password: => Option[String] = None,
                         chunkSize: Int = Defaults.defaultChunkSize): Zip4JArchiver[F] =
    new Zip4JArchiver(password, chunkSize)
}

class Zip4JUnarchiver[F[_] : Async](chunkSize: Int) extends Unarchiver[F, Option, LocalFileHeader] {
  def unarchive: Pipe[F, Byte, (ArchiveEntry[Option, LocalFileHeader], Stream[F, Byte])] = { stream =>
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
        def readEntries: Stream[F, (ArchiveEntry[Option, LocalFileHeader], Stream[F, Byte])] =
          Stream.resource(Resource.make(
              Async[F].blocking(Option(zipInputStream.getNextEntry))
            )(_ =>
              Async[F].unit //.blocking(zipInputStream.closeEntry())
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

object Zip4JUnarchiver {
  def apply[F[_]](implicit instance: Zip4JUnarchiver[F]): Zip4JUnarchiver[F] = instance

  def make[F[_] : Async](chunkSize: Int = Defaults.defaultChunkSize): Zip4JUnarchiver[F] =
    new Zip4JUnarchiver(chunkSize)
}
