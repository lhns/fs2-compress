package de.lhns.fs2.compress

import cats.effect.IO

class Brotli4JCancellationSuite extends CompressorCancellationSuite {
  // This decompressor needs a whole internal block for every read, so how long a cancellation
  // takes depends on the source rather than on this library. See
  // CompressorCancellationSuite.decompressorReadsAreFineGrained.
  override protected def decompressorReadsAreFineGrained: Boolean = false

  override protected def compressor(chunkSize: Int): Option[Compressor[IO]] =
    Some(Brotli4JCompressor.make[IO](chunkSize = chunkSize))

  override protected def decompressor(chunkSize: Int): Decompressor[IO] =
    Brotli4JDecompressor.make[IO](chunkSize)
}
