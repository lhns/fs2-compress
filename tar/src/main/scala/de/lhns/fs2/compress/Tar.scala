package de.lhns.fs2.compress

import cats.effect.{Async, Deferred, Resource}
import cats.syntax.functor._
import fs2.io._
import fs2.{Pipe, Stream}
import org.apache.commons.compress.archivers.tar.{TarArchiveEntry, TarArchiveInputStream, TarArchiveOutputStream}
import org.apache.commons.compress.archivers.{ArchiveEntry => CommonsArchiveEntry}

import java.io.{BufferedInputStream, InputStream, OutputStream}
import java.nio.file.attribute.FileTime
import java.time.Instant

object Tar {
  implicit val tarArchiveEntry: ArchiveEntry[CommonsArchiveEntry] = new ArchiveEntry[CommonsArchiveEntry] {
    override def name(entry: CommonsArchiveEntry): String =
      entry.getName

    override def size(entry: CommonsArchiveEntry): Option[Long] =
      Some(entry.getSize).filterNot(_ == CommonsArchiveEntry.SIZE_UNKNOWN)

    override def isDirectory(entry: CommonsArchiveEntry): Boolean =
      entry.isDirectory

    override def lastModified(entry: CommonsArchiveEntry): Option[Instant] =
      Option(entry.getLastModifiedDate).map(_.toInstant)
  }


  implicit val tarArchiveEntryConstructor: ArchiveEntryConstructor[TarArchiveEntry] = new ArchiveEntryConstructor[TarArchiveEntry] {
    override def apply(
                        name: String,
                        size: Option[Long],
                        isDirectory: Boolean,
                        lastModified: Option[Instant]
                      ): TarArchiveEntry = {
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

  }
}

class TarArchiver[F[_] : Async](chunkSize: Int) extends Archiver[F, TarArchiveEntry] {
  override def archiveEntryConstructor: ArchiveEntryConstructor[TarArchiveEntry] = Tar.tarArchiveEntryConstructor

  override def archiveEntry: ArchiveEntry[TarArchiveEntry] = Tar.tarArchiveEntry

  override def archive: Pipe[F, (TarArchiveEntry, Stream[F, Byte]), Byte] = { stream =>
    readOutputStream[F](chunkSize) { outputStream =>
      Resource.make(Async[F].delay {
        new TarArchiveOutputStream(outputStream)
      })(s =>
        Async[F].blocking(s.close())
      ).use { tarOutputStream =>
        stream
          .flatMap {
            case (tarEntry, stream) =>
              stream
                .chunkAll
                .flatMap { chunk =>
                  tarEntry.setSize(chunk.size)
                  val stream = Stream.chunk(chunk).covary[F]
                  Stream.resource(Resource.make(
                    Async[F].blocking(tarOutputStream.putArchiveEntry(tarEntry))
                  )(_ =>
                    Async[F].blocking(tarOutputStream.closeArchiveEntry())
                  ))
                    .flatMap(_ =>
                      stream
                        .through(writeOutputStream(Async[F].pure[OutputStream](tarOutputStream), closeAfterUse = false))
                    )
                }
          }
          .compile
          .drain
      }
    }
  }
}

object TarArchiver {
  def apply[F[_] : Async](chunkSize: Int = Defaults.defaultChunkSize): TarArchiver[F] =
    new TarArchiver(chunkSize)
}

class TarUnarchiver[F[_] : Async](chunkSize: Int) extends Unarchiver[F, TarArchiveEntry] {
  override def archiveEntry: ArchiveEntry[TarArchiveEntry] = Tar.tarArchiveEntry

  override def unarchive: Pipe[F, Byte, (TarArchiveEntry, Stream[F, Byte])] = { stream =>
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
        def readEntries: Stream[F, (TarArchiveEntry, Stream[F, Byte])] =
          Stream.eval(Async[F].blocking(Option(tarInputStream.getNextTarEntry)))
            .flatMap(Stream.fromOption[F](_))
            .flatMap { tarEntry =>
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
  def apply[F[_] : Async](chunkSize: Int = Defaults.defaultChunkSize): TarUnarchiver[F] =
    new TarUnarchiver(chunkSize)
}
