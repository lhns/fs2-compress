package de.lhns.fs2.compress

import cats.effect.Async
import fs2.Pipe
import fs2.compression.Compression

class GzipCompressor[F[_]: Async: Compression] private (
    deflateLevel: Option[Int],
    deflateStrategy: Option[Int],
    chunkSize: Int
) extends Compressor[F] {
  override def compress: Pipe[F, Byte, Byte] =
    fs2.compression.Compression[F].gzip(chunkSize, deflateLevel, deflateStrategy)
}

object GzipCompressor {
  def apply[F[_]](implicit instance: GzipCompressor[F]): GzipCompressor[F] = instance

  def make[F[_]: Async: Compression](
      deflateLevel: Option[Int] = None,
      deflateStrategy: Option[Int] = None,
      chunkSize: Int = Defaults.defaultChunkSize
  ): GzipCompressor[F] =
    new GzipCompressor(deflateLevel, deflateStrategy, chunkSize)
}

class GzipDecompressor[F[_]: Async: Compression] private (chunkSize: Int) extends Decompressor[F] {
  override def decompress: Pipe[F, Byte, Byte] =
    fs2.compression.Compression[F].gunzip(chunkSize).andThen(_.flatMap(_.content))
}

object GzipDecompressor {
  def apply[F[_]](implicit instance: GzipDecompressor[F]): GzipDecompressor[F] = instance

  def make[F[_]: Async: Compression](chunkSize: Int = Defaults.defaultChunkSize): GzipDecompressor[F] =
    new GzipDecompressor(chunkSize)
}
