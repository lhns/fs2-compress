package de.lhns.fs2.compress

import cats.effect.{Async, Deferred, Resource}
import cats.syntax.functor._
import fs2.io._
import fs2.{Pipe, Stream}

import java.io.{BufferedInputStream, InputStream, OutputStream}
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.zip.{ZipEntry, ZipInputStream, ZipOutputStream}

object Zip {
  implicit val zipArchiveEntry: ArchiveEntry[ZipEntry] = new ArchiveEntry[ZipEntry] {
    override def name(entry: ZipEntry): String = entry.getName

    override def size(entry: ZipEntry): Option[Long] = Some(entry.getSize).filterNot(_ == -1)

    override def isDirectory(entry: ZipEntry): Boolean = entry.isDirectory

    override def lastModified(entry: ZipEntry): Option[Instant] =
      Option(entry.getLastModifiedTime).map(_.toInstant)
  }


  implicit val zipArchiveEntryConstructor: ArchiveEntryConstructor[ZipEntry] = new ArchiveEntryConstructor[ZipEntry] {
    override def apply(
                        name: String,
                        size: Option[Long],
                        isDirectory: Boolean,
                        lastModified: Option[Instant]
                      ): ZipEntry = {
      val fileOrDirName = name match {
        case name if isDirectory && !name.endsWith("/") => name + "/"
        case name if !isDirectory && name.endsWith("/") => name.dropRight(1)
        case name => name
      }
      val entry = new ZipEntry(fileOrDirName)
      size.foreach(entry.setSize)
      lastModified.map(FileTime.from).foreach(entry.setLastModifiedTime)
      entry
    }
  }
}

class ZipArchiver[F[_] : Async](method: Int, chunkSize: Int) extends Archiver[F, ZipEntry] {
  override def archiveEntryConstructor: ArchiveEntryConstructor[ZipEntry] = Zip.zipArchiveEntryConstructor

  override def archiveEntry: ArchiveEntry[ZipEntry] = Zip.zipArchiveEntry

  def archive: Pipe[F, (ZipEntry, Stream[F, Byte]), Byte] = { stream =>
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
            case (zipEntry, stream) =>
              stream
                .chunkAll
                .flatMap { chunk =>
                  zipEntry.setSize(chunk.size)
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

object ZipArchiver {
  def apply[F[_]](implicit instance: ZipArchiver[F]): ZipArchiver[F] = instance

  def make[F[_] : Async](method: Int = ZipOutputStream.DEFLATED,
                          chunkSize: Int = Defaults.defaultChunkSize): ZipArchiver[F] =
    new ZipArchiver(method, chunkSize)
}

class ZipUnarchiver[F[_] : Async](chunkSize: Int) extends Unarchiver[F, ZipEntry] {
  override def archiveEntry: ArchiveEntry[ZipEntry] = Zip.zipArchiveEntry

  def unarchive: Pipe[F, Byte, (ZipEntry, Stream[F, Byte])] = { stream =>
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
        def readEntries: Stream[F, (ZipEntry, Stream[F, Byte])] =
          Stream.resource(Resource.make(
            Async[F].blocking(Option(zipInputStream.getNextEntry))
          )(_ =>
            Async[F].blocking(zipInputStream.closeEntry())
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

object ZipUnarchiver {
  def apply[F[_]](implicit instance: ZipUnarchiver[F]): ZipUnarchiver[F] = instance

  def make[F[_] : Async](chunkSize: Int = Defaults.defaultChunkSize): ZipUnarchiver[F] =
    new ZipUnarchiver(chunkSize)
}
