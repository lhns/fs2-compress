package de.lhns.fs2.compress

import cats.effect.IO

class Lz4CancellationSuite extends CompressorCancellationSuite {
  override protected def compressor(chunkSize: Int): Option[Compressor[IO]] =
    Some(Lz4Compressor.make[IO](chunkSize = chunkSize))

  override protected def decompressor(chunkSize: Int): Decompressor[IO] =
    Lz4Decompressor.make[IO](chunkSize)
}
