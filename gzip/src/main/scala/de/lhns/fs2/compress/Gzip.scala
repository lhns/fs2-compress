package de.lhns.fs2.compress

import cats.effect.{Async, LiftIO}
import fs2.Pipe
import fs2.io.compression._

class GzipCompressor[F[_] : Async : LiftIO](deflateLevel: Option[Int],
                                            deflateStrategy: Option[Int],
                                            chunkSize: Int) extends Compressor[F] {
  override def compress: Pipe[F, Byte, Byte] =
    fs2.compression.Compression[F].gzip(chunkSize, deflateLevel, deflateStrategy)
}

object GzipCompressor {
  def apply[F[_] : Async : LiftIO](deflateLevel: Option[Int] = None,
                                   deflateStrategy: Option[Int] = None,
                                   chunkSize: Int = Defaults.defaultChunkSize): GzipCompressor[F] =
    new GzipCompressor(deflateLevel, deflateStrategy, chunkSize)
}

class GzipDecompressor[F[_] : Async : LiftIO](chunkSize: Int) extends Decompressor[F] {
  override def decompress: Pipe[F, Byte, Byte] =
    fs2.compression.Compression[F].gunzip(chunkSize).andThen(_.flatMap(_.content))
}

object GzipDecompressor {
  def apply[F[_] : Async : LiftIO](chunkSize: Int = Defaults.defaultChunkSize): GzipDecompressor[F] =
    new GzipDecompressor(chunkSize)
}
