package de.lhns.fs2.compress

import net.jpountz.lz4.{LZ4FrameOutputStream, LZ4FrameInputStream}
import cats.effect.Async
import fs2.Pipe
import fs2.io._

import java.io.{BufferedInputStream, OutputStream}

class Lz4Compressor[F[_]: Async] private (chunkSize: Int) extends Compressor[F] {
  override def compress: Pipe[F, Byte, Byte] = { stream =>
    readOutputStream[F](chunkSize) { outputStream =>
      stream
        .through(writeOutputStream(Async[F].blocking[OutputStream] {
          new LZ4FrameOutputStream(outputStream, LZ4FrameOutputStream.BLOCKSIZE.SIZE_256KB)
        }))
        .compile
        .drain
    }
  }
}

object Lz4Compressor {
  def apply[F[_]](implicit instance: Lz4Compressor[F]): Lz4Compressor[F] = instance

  def make[F[_]: Async](
      chunkSize: Int = Defaults.defaultChunkSize
  ): Lz4Compressor[F] =
    new Lz4Compressor(chunkSize)
}

class Lz4Decompressor[F[_]: Async] private (chunkSize: Int) extends Decompressor[F] {
  override def decompress: Pipe[F, Byte, Byte] = { stream =>
    stream
      .through(toInputStream[F])
      .map(new BufferedInputStream(_, chunkSize))
      .flatMap { inputStream =>
        readInputStream(
          Async[F].blocking(new LZ4FrameInputStream(inputStream)),
          chunkSize
        )
      }
  }
}

object Lz4Decompressor {
  def apply[F[_]](implicit instance: Lz4Decompressor[F]): Lz4Decompressor[F] = instance

  def make[F[_]: Async](chunkSize: Int = Defaults.defaultChunkSize): Lz4Decompressor[F] =
    new Lz4Decompressor(chunkSize)
}
