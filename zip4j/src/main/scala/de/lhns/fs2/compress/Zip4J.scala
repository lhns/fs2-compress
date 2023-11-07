package de.lhns.fs2.compress

import cats.effect.{Async, Deferred, Resource}
import cats.syntax.functor._
import fs2.io.{readInputStream, readOutputStream, toInputStream, writeOutputStream}
import fs2.{Pipe, Stream}
import net.lingala.zip4j.io.inputstream.ZipInputStream
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters

import java.io.{BufferedInputStream, InputStream, OutputStream}
import java.time.Instant

object Zip4J {
  implicit val zipArchiveEntry: ArchiveEntry[ZipParameters] = new ArchiveEntry[ZipParameters] {
    override def name(entry: ZipParameters): String = entry.getFileNameInZip

    override def size(entry: ZipParameters): Option[Long] = Some(entry.getEntrySize).filterNot(_ == -1)

    override def isDirectory(entry: ZipParameters): Boolean = false //TODO entry.isDirectory

    override def lastModified(entry: ZipParameters): Option[Instant] =
      Option(entry.getLastModifiedFileTime).map(Instant.ofEpochMilli)
  }


  implicit val zipArchiveEntryConstructor: ArchiveEntryConstructor[ZipParameters] = new ArchiveEntryConstructor[ZipParameters] {
    override def apply(
                        name: String,
                        size: Option[Long],
                        isDirectory: Boolean,
                        lastModified: Option[Instant]
                      ): ZipParameters = {
      val fileOrDirName = name match {
        case name if isDirectory && !name.endsWith("/") => name + "/"
        case name if !isDirectory && name.endsWith("/") => name.dropRight(1)
        case name => name
      }
      val entry = new ZipParameters()
      entry.setFileNameInZip(fileOrDirName)
      size.foreach(entry.setEntrySize)
      lastModified.map(_.toEpochMilli).foreach(entry.setLastModifiedFileTime)
      entry
    }
  }
}

class Zip4JArchiver[F[_] : Async](method: Int, chunkSize: Int) extends Archiver[F, ZipParameters] {
  override def archiveEntryConstructor: ArchiveEntryConstructor[ZipParameters] = Zip4J.zipArchiveEntryConstructor

  override def archiveEntry: ArchiveEntry[ZipParameters] = Zip4J.zipArchiveEntry

  def archive: Pipe[F, (ZipParameters, Stream[F, Byte]), Byte] = { stream =>
    readOutputStream[F](chunkSize) { outputStream =>
      Resource.make(Async[F].delay {
        val zipOutputStream = new ZipOutputStream(outputStream, "password".toCharArray)
        //zipOutputStream.setMethod(method)
        zipOutputStream
      })(zipOutputStream =>
        Async[F].blocking(zipOutputStream.close())
      ).use { zipOutputStream =>
        stream
          .flatMap {
            case (zipEntry, stream) =>
              stream
                .chunkAll
                .flatMap { chunk =>
                  zipEntry.setEntrySize(chunk.size)
                  val stream = Stream.chunk(chunk).covary[F]
                  Stream.resource(Resource.make(
                      Async[F].blocking(zipOutputStream.putNextEntry(zipEntry))
                    )(_ =>
                      Async[F].blocking(zipOutputStream.closeEntry())
                    ))
                    .flatMap(_ =>
                      stream
                        .through(writeOutputStream(Async[F].pure[OutputStream](zipOutputStream), closeAfterUse = false))
                    )
                }
          }
          .compile
          .drain
      }
    }
  }
}

object Zip4JArchiver {
  def apply[F[_]](implicit instance: Zip4JArchiver[F]): Zip4JArchiver[F] = instance

  def make[F[_] : Async](method: Int = ZipOutputStream.DEFLATED,
                         chunkSize: Int = Defaults.defaultChunkSize): Zip4JArchiver[F] =
    new Zip4JArchiver(method, chunkSize)
}

class Zip4JUnarchiver[F[_] : Async](chunkSize: Int) extends Unarchiver[F, ZipParameters] {
  override def archiveEntry: ArchiveEntry[ZipParameters] = Zip4J.zipArchiveEntry

  def unarchive: Pipe[F, Byte, (ZipParameters, Stream[F, Byte])] = { stream =>
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
        def readEntries: Stream[F, (ZipParameters, Stream[F, Byte])] =
          Stream.resource(Resource.make(
              Async[F].blocking(Option(zipInputStream.getNextEntry))
            )(_ =>
              Async[F].unit //.blocking(zipInputStream.closeEntry())
            ))
            .flatMap(Stream.fromOption[F](_))
            .flatMap { zipEntry =>
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

object Zip4JUnarchiver {
  def apply[F[_]](implicit instance: Zip4JUnarchiver[F]): Zip4JUnarchiver[F] = instance

  def make[F[_] : Async](chunkSize: Int = Defaults.defaultChunkSize): Zip4JUnarchiver[F] =
    new Zip4JUnarchiver(chunkSize)
}
