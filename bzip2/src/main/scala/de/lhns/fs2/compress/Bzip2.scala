package de.lhns.fs2.compress

import cats.effect.Async
import fs2.Pipe
import fs2.io._
import org.apache.commons.compress.compressors.bzip2.{BZip2CompressorInputStream, BZip2CompressorOutputStream}

import java.io.{BufferedInputStream, OutputStream}

class Bzip2Compressor[F[_] : Async] private(blockSize: Option[Int], chunkSize: Int) extends Compressor[F] {
  override def compress: Pipe[F, Byte, Byte] = { stream =>
    readOutputStream[F](chunkSize) { outputStream =>
      stream
        .through(
          writeOutputStream(
            Async[F].blocking[OutputStream](
              blockSize.fold(
                new BZip2CompressorOutputStream(outputStream)
              )(
                new BZip2CompressorOutputStream(outputStream, _)
              )
            )
          )
        )
        .compile
        .drain
    }
  }
}

object Bzip2Compressor {
  def apply[F[_]](implicit instance: Bzip2Compressor[F]): Bzip2Compressor[F] = instance

  def make[F[_] : Async](
                          blockSize: Option[Int] = None,
                          chunkSize: Int = Defaults.defaultChunkSize
                        ): Bzip2Compressor[F] = new Bzip2Compressor(blockSize, chunkSize)
}

class Bzip2Decompressor[F[_] : Async] private(chunkSize: Int) extends Decompressor[F] {
  override def decompress: Pipe[F, Byte, Byte] = { stream =>
    stream
      .through(toInputStream[F])
      .map(new BufferedInputStream(_, chunkSize))
      .flatMap { inputStream =>
        readInputStream(
          Async[F].blocking(new BZip2CompressorInputStream(inputStream)),
          chunkSize
        )
      }
  }
}

object Bzip2Decompressor {
  def apply[F[_]](implicit instance: Bzip2Decompressor[F]): Bzip2Decompressor[F] = instance

  def make[F[_] : Async](chunkSize: Int = Defaults.defaultChunkSize): Bzip2Decompressor[F] =
    new Bzip2Decompressor(chunkSize)
}
