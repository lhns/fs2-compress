package de.lhns.fs2.compress

import cats.effect.IO
import fs2.io.compression._

class GzipMemorySuite extends MemorySuite {
  override protected def compressor: Compressor[IO] = GzipCompressor.make[IO]()
  override protected def decompressor: Decompressor[IO] = GzipDecompressor.make[IO]()
}
