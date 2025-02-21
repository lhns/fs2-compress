package de.lhns.fs2.compress

import cats.effect.Async
import fs2.Pipe
import fs2.io._
import org.brotli.dec.BrotliInputStream

class BrotliDecompressor[F[_]: Async] private (chunkSize: Int) extends Decompressor[F] {
  override def decompress: Pipe[F, Byte, Byte] = { stream =>
    stream
      .through(toInputStream[F])
      .flatMap { inputStream =>
        readInputStream(
          Async[F].blocking(new BrotliInputStream(inputStream)),
          chunkSize
        )
      }
  }
}

object BrotliDecompressor {
  def defaultChunkSize: Int = BrotliInputStream.DEFAULT_INTERNAL_BUFFER_SIZE

  def apply[F[_]](implicit instance: BrotliDecompressor[F]): BrotliDecompressor[F] = instance

  def make[F[_]: Async](chunkSize: Int = defaultChunkSize): BrotliDecompressor[F] =
    new BrotliDecompressor(chunkSize)

  def decompress[F[_]: Async](chunkSize: Int = defaultChunkSize): Pipe[F, Byte, Byte] =
    make[F](chunkSize).decompress
}
