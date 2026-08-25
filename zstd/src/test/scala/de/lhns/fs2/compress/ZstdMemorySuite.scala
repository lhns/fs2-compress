package de.lhns.fs2.compress

import cats.effect.IO


class ZstdMemorySuite extends MemorySuite {
  override protected def compressor: Compressor[IO] = ZstdCompressor.make[IO]()
  override protected def decompressor: Decompressor[IO] = ZstdDecompressor.make[IO]()
}
