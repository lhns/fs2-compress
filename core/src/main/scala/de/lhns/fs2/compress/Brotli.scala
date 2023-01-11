package de.lhns.fs2.compress

import cats.effect.Async
import fs2.Pipe
import fs2.io._
import org.apache.commons.compress.compressors.brotli.BrotliCompressorInputStream

import java.io.BufferedInputStream

class BrotliDecompressor[F[_] : Async](chunkSize: Int) extends Decompressor[F] {
  override def decompress: Pipe[F, Byte, Byte] = { stream =>
    stream
      .through(toInputStream[F]).map(new BufferedInputStream(_, chunkSize))
      .flatMap { inputStream =>
        readInputStream(
          Async[F].blocking(new BrotliCompressorInputStream(inputStream)),
          chunkSize
        )
      }
  }
}

object BrotliDecompressor {
  def apply[F[_] : Async](chunkSize: Int = Defaults.defaultChunkSize): BrotliDecompressor[F] =
    new BrotliDecompressor(chunkSize)
}
