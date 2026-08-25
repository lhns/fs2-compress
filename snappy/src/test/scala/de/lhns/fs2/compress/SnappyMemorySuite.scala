package de.lhns.fs2.compress

import cats.effect.IO

class SnappyMemorySuite extends MemorySuite {
  override protected def compressor: Compressor[IO] =
    SnappyCompressor.make[IO](mode = SnappyCompressor.WriteMode.Framed())
  override protected def decompressor: Decompressor[IO] =
    SnappyDecompressor.make[IO](mode = SnappyDecompressor.ReadMode.Framed())
}
