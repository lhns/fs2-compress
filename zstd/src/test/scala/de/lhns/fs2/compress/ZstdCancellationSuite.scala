package de.lhns.fs2.compress

import cats.effect.IO

class ZstdCancellationSuite extends CompressorCancellationSuite {
  override protected def compressor(chunkSize: Int): Option[Compressor[IO]] =
    Some(ZstdCompressor.make[IO](chunkSize = chunkSize))

  override protected def decompressor(chunkSize: Int): Decompressor[IO] =
    ZstdDecompressor.make[IO](chunkSize)
}
