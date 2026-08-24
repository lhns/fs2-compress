package de.lhns.fs2.compress

import cats.effect.{Async, Resource}
import com.github.luben.zstd.{ZstdInputStream, ZstdOutputStream}
import fs2.{Pipe, Stream}
import fs2.io._

import java.io.{BufferedInputStream, OutputStream}

class ZstdCompressor[F[_]: Async] private (level: Option[Int], workers: Option[Int], chunkSize: Int)
    extends Compressor[F] {
  override def compress: Pipe[F, Byte, Byte] = { stream =>
    readOutputStream[F](chunkSize) { outputStream =>
      Resource
        .make(Async[F].blocking[OutputStream] {
          val zstdOutputStream = new ZstdOutputStream(outputStream)
          level.foreach(zstdOutputStream.setLevel)
          workers.foreach(zstdOutputStream.setWorkers)
          zstdOutputStream
        })(os =>
          // Safety net for the paths where the stream above did not get to close `os` itself:
          // cancellation, or an error. Closing the pipe first is what makes this non-blocking - writes
          // to a closed PipedStreamBuffer are no-ops rather than errors, so `close()` runs to
          // completion and still frees what it owns, instead of blocking forever on a consumer that
          // stopped draining (#113). Any genuine close error has already surfaced from the stream.
          Async[F].void(Async[F].attempt(Async[F].blocking {
            outputStream.close()
            os.close()
          }))
        )
        .use { os =>
          (stream
            .through(writeOutputStream(Async[F].pure(os), closeAfterUse = false)) ++
            Stream.exec(Async[F].interruptible(os.close()))).compile.drain
        }
    }
  }
}

object ZstdCompressor {
  def apply[F[_]](implicit instance: ZstdCompressor[F]): ZstdCompressor[F] = instance

  def make[F[_]: Async](
      level: Option[Int] = None,
      workers: Option[Int] = None,
      chunkSize: Int = Defaults.defaultChunkSize
  ): ZstdCompressor[F] =
    new ZstdCompressor(level, workers, chunkSize)
}

class ZstdDecompressor[F[_]: Async] private (chunkSize: Int) extends Decompressor[F] {
  override def decompress: Pipe[F, Byte, Byte] = { stream =>
    stream
      .through(toInputStream[F])
      .map(new BufferedInputStream(_, chunkSize))
      .flatMap { inputStream =>
        readInputStream(
          Async[F].blocking(new ZstdInputStream(inputStream)),
          chunkSize
        )
      }
  }
}

object ZstdDecompressor {
  def apply[F[_]](implicit instance: ZstdDecompressor[F]): ZstdDecompressor[F] = instance

  def make[F[_]: Async](chunkSize: Int = Defaults.defaultChunkSize): ZstdDecompressor[F] =
    new ZstdDecompressor(chunkSize)
}
