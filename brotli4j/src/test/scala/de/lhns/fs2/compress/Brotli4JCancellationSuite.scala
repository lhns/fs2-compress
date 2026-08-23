package de.lhns.fs2.compress

import cats.effect.IO

class Brotli4JCancellationSuite extends CompressorCancellationSuite {
  // See CompressorCancellationSuite.decompressorReadsAreFineGrained: this decompressor needs a
  // whole internal block per read, so cancellation latency is bounded by the source, not by us.
  override protected def decompressorReadsAreFineGrained: Boolean = false

  override protected def compressor(chunkSize: Int): Option[Compressor[IO]] =
    Some(Brotli4JCompressor.make[IO](chunkSize = chunkSize))

  override protected def decompressor(chunkSize: Int): Decompressor[IO] =
    Brotli4JDecompressor.make[IO](chunkSize)
}
