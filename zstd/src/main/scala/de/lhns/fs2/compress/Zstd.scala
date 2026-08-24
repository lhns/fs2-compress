package de.lhns.fs2.compress

import cats.effect.{Async, Resource}
import com.github.luben.zstd.{ZstdInputStream, ZstdOutputStream}
import fs2.{Pipe, Stream}
import fs2.io._

import java.io.{BufferedInputStream, OutputStream}

class ZstdCompressor[F[_]: Async] private (level: Option[Int], workers: Option[Int], chunkSize: Int)
    extends Compressor[F] {
  override def compress: Pipe[F, Byte, Byte] =
    OutputStreams.compress[F](chunkSize) { outputStream =>
      val zstdOutputStream = new ZstdOutputStream(outputStream)
      level.foreach(zstdOutputStream.setLevel)
      workers.foreach(zstdOutputStream.setWorkers)
      zstdOutputStream
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
