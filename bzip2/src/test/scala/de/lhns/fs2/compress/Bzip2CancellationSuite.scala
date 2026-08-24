package de.lhns.fs2.compress

import cats.effect.IO

class Bzip2CancellationSuite extends CompressorCancellationSuite {
  // This decompressor needs a whole internal block for every read, so how long a cancellation
  // takes depends on the source rather than on this library. See
  // CompressorCancellationSuite.decompressorReadsAreFineGrained.
  override protected def decompressorReadsAreFineGrained: Boolean = false

  override protected def compressor(chunkSize: Int): Option[Compressor[IO]] =
    Some(Bzip2Compressor.make[IO](chunkSize = chunkSize))

  override protected def decompressor(chunkSize: Int): Decompressor[IO] =
    Bzip2Decompressor.make[IO](chunkSize)
}
