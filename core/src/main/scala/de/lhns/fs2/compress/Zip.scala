package de.lhns.fs2.compress

import cats.effect.{Async, Deferred, Resource}
import cats.syntax.functor._
import fs2.io._
import fs2.{Pipe, Stream}

import java.io.{BufferedInputStream, InputStream, OutputStream}
import java.util.zip.{ZipEntry, ZipInputStream, ZipOutputStream}

class ZipArchiver[F[_] : Async](method: Int, chunkSize: Int) {
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
  def apply[F[_] : Async](method: Int = ZipOutputStream.DEFLATED,
                          chunkSize: Int = Defaults.defaultChunkSize): ZipArchiver[F] =
    new ZipArchiver(method, chunkSize)
}

class ZipUnarchiver[F[_] : Async](chunkSize: Int) {
  def unarchive: Pipe[F, Byte, (ZipEntry, Stream[F, Byte])] = { stream =>
    stream
      .through(toInputStream[F]).map(new BufferedInputStream(_, chunkSize))
      .flatMap { inputStream =>
        Stream.resource(Resource.make(
          Async[F].blocking(new ZipInputStream(inputStream))
        )(zipInputStream =>
          Async[F].blocking(zipInputStream.close())
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
  def apply[F[_] : Async](chunkSize: Int = Defaults.defaultChunkSize): ZipUnarchiver[F] =
    new ZipUnarchiver(chunkSize)
}
