package de.lhns.fs2.compress

import cats.effect.IO

class Brotli4JCancellationSuite extends CompressorCancellationSuite {
  override protected def compressor(chunkSize: Int): Option[Compressor[IO]] =
    Some(Brotli4JCompressor.make[IO](chunkSize = chunkSize))

  override protected def decompressor(chunkSize: Int): Decompressor[IO] =
    Brotli4JDecompressor.make[IO](chunkSize)
}
