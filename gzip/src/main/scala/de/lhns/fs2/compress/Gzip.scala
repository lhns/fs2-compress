package de.lhns.fs2.compress

import cats.effect.Async
import fs2.Pipe
import fs2.compression.Compression

class GzipCompressor[F[_] : Async](deflateLevel: Option[Int],
                                   deflateStrategy: Option[Int],
                                   chunkSize: Int) extends Compressor[F] {
  private val compression: Compression[F] = Compression.forSync

  override def compress: Pipe[F, Byte, Byte] =
    compression.gzip(chunkSize, deflateLevel, deflateStrategy)
}

object GzipCompressor {
  def apply[F[_] : Async](deflateLevel: Option[Int] = None,
                          deflateStrategy: Option[Int] = None,
                          chunkSize: Int = Defaults.defaultChunkSize): GzipCompressor[F] =
    new GzipCompressor(deflateLevel, deflateStrategy, chunkSize)
}

class GzipDecompressor[F[_] : Async](chunkSize: Int) extends Decompressor[F] {
  private val compression: Compression[F] = Compression.forSync

  override def decompress: Pipe[F, Byte, Byte] =
    compression.gunzip(chunkSize).andThen(_.flatMap(_.content))
}

object GzipDecompressor {
  def apply[F[_] : Async](chunkSize: Int = Defaults.defaultChunkSize): GzipDecompressor[F] =
    new GzipDecompressor(chunkSize)
}
