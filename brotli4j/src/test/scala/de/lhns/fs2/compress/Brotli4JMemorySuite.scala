package de.lhns.fs2.compress

import cats.effect.IO

class Brotli4JMemorySuite extends MemorySuite {
  override protected def compressor: Compressor[IO] = Brotli4JCompressor.make[IO]()
  override protected def decompressor: Decompressor[IO] = Brotli4JDecompressor.make[IO]()
}
