package de.lhns.fs2.compress

import cats.effect.IO

class SnappyBasicCancellationSuite extends CompressorCancellationSuite {
  // See CompressorCancellationSuite.decompressorReadsAreFineGrained: this decompressor needs a
  // whole internal block per read, so cancellation latency is bounded by the source, not by us.
  override protected def decompressorReadsAreFineGrained: Boolean = false

  override protected def compressor(chunkSize: Int): Option[Compressor[IO]] =
    Some(SnappyCompressor.make[IO](chunkSize, SnappyCompressor.WriteMode.Basic()))

  override protected def decompressor(chunkSize: Int): Decompressor[IO] =
    SnappyDecompressor.make[IO](chunkSize, SnappyDecompressor.ReadMode.Basic())
}

class SnappyFramedCancellationSuite extends CompressorCancellationSuite {
  // See CompressorCancellationSuite.decompressorReadsAreFineGrained: this decompressor needs a
  // whole internal block per read, so cancellation latency is bounded by the source, not by us.
  override protected def decompressorReadsAreFineGrained: Boolean = false

  override protected def compressor(chunkSize: Int): Option[Compressor[IO]] =
    Some(SnappyCompressor.make[IO](chunkSize, SnappyCompressor.WriteMode.Framed()))

  override protected def decompressor(chunkSize: Int): Decompressor[IO] =
    SnappyDecompressor.make[IO](chunkSize, SnappyDecompressor.ReadMode.Framed())
}
