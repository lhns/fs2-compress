package de.lhns.fs2.compress

import cats.effect.IO

class Lz4MemorySuite extends MemorySuite {
  override protected def compressor: Compressor[IO] = Lz4Compressor.make[IO]()
  override protected def decompressor: Decompressor[IO] = Lz4Decompressor.make[IO]()
}
