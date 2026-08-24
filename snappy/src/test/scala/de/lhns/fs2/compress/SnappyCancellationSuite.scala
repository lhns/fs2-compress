package de.lhns.fs2.compress

import cats.effect.IO

class SnappyBasicCancellationSuite extends CompressorCancellationSuite {
  // This decompressor needs a whole internal block for every read, so how long a cancellation
  // takes depends on the source rather than on this library. See
  // CompressorCancellationSuite.decompressorReadsAreFineGrained.
  override protected def decompressorReadsAreFineGrained: Boolean = false

  override protected def compressor(chunkSize: Int): Option[Compressor[IO]] =
    Some(SnappyCompressor.make[IO](chunkSize, SnappyCompressor.WriteMode.Basic()))

  override protected def decompressor(chunkSize: Int): Decompressor[IO] =
    SnappyDecompressor.make[IO](chunkSize, SnappyDecompressor.ReadMode.Basic())
}

class SnappyFramedCancellationSuite extends CompressorCancellationSuite {
  // This decompressor needs a whole internal block for every read, so how long a cancellation
  // takes depends on the source rather than on this library. See
  // CompressorCancellationSuite.decompressorReadsAreFineGrained.
  override protected def decompressorReadsAreFineGrained: Boolean = false

  override protected def compressor(chunkSize: Int): Option[Compressor[IO]] =
    Some(SnappyCompressor.make[IO](chunkSize, SnappyCompressor.WriteMode.Framed()))

  override protected def decompressor(chunkSize: Int): Decompressor[IO] =
    SnappyDecompressor.make[IO](chunkSize, SnappyDecompressor.ReadMode.Framed())
}
