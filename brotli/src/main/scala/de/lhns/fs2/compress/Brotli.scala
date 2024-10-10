package de.lhns.fs2.compress

import cats.effect.Async
import com.aayushatharva.brotli4j.Brotli4jLoader
import com.aayushatharva.brotli4j.decoder.BrotliInputStream
import fs2.{Pipe, Stream}
import fs2.io._

class BrotliDecompressor[F[_]: Async] private (chunkSize: Int) extends Decompressor[F] {
  override def decompress: Pipe[F, Byte, Byte] = { stream =>
    Stream.exec(Async[F].blocking(Brotli4jLoader.ensureAvailability())).covaryOutput[Byte] ++
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
  def defaultChunkSize: Int = 16384

  def apply[F[_]](implicit instance: BrotliDecompressor[F]): BrotliDecompressor[F] = instance

  def make[F[_]: Async](chunkSize: Int = defaultChunkSize): BrotliDecompressor[F] =
    new BrotliDecompressor(chunkSize)
}
