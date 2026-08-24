package de.lhns.fs2.compress

import cats.effect.IO

class Bzip2CancellationSuite extends CompressorCancellationSuite {
  override protected def compressor(chunkSize: Int): Option[Compressor[IO]] =
    Some(Bzip2Compressor.make[IO](chunkSize = chunkSize))

  override protected def decompressor(chunkSize: Int): Decompressor[IO] =
    Bzip2Decompressor.make[IO](chunkSize)
}
