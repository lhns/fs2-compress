package de.lhns.fs2.compress

import cats.effect.Async
import com.aayushatharva.brotli4j.Brotli4jLoader
import com.aayushatharva.brotli4j.encoder.{BrotliOutputStream, Encoder}
import com.aayushatharva.brotli4j.decoder.BrotliInputStream
import fs2.{Pipe, Stream}
import fs2.io._

class Brotli4JCompressor[F[_]: Async] private (chunkSize: Int, params: Encoder.Parameters) extends Compressor[F] {
  override def compress: Pipe[F, Byte, Byte] = { stream =>
    Stream.exec(Async[F].blocking(Brotli4jLoader.ensureAvailability())).covaryOutput[Byte] ++
      readOutputStream[F](chunkSize) { outputStream =>
        stream
          .through(
            writeOutputStream[F](Async[F].blocking(new BrotliOutputStream(outputStream, params)))
          )
          .compile
          .drain
      }
  }
}

object Brotli4JCompressor {
  // Trigger loading the native library.
  new Brotli4jLoader()

  def apply[F[_]](implicit instance: Brotli4JCompressor[F]): Brotli4JCompressor[F] = instance

  def make[F[_]: Async](
      chunkSize: Int = Defaults.defaultChunkSize,
      params: Encoder.Parameters = Encoder.Parameters.DEFAULT
  ): Brotli4JCompressor[F] =
    new Brotli4JCompressor(chunkSize, params)
}

class Brotli4JDecompressor[F[_]: Async] private (chunkSize: Int) extends Decompressor[F] {
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

object Brotli4JDecompressor {
  // Trigger loading the native library.
  new Brotli4jLoader()

  // Defined as DEFAULT_BUFFER_SIZE in BrotliInputStream, but isn't public
  def defaultChunkSize: Int = 16384

  def apply[F[_]](implicit instance: Brotli4JDecompressor[F]): Brotli4JDecompressor[F] = instance

  def make[F[_]: Async](chunkSize: Int = defaultChunkSize): Brotli4JDecompressor[F] =
    new Brotli4JDecompressor(chunkSize)
}
