package de.lhns.fs2.compress

import cats.effect.IO


class Bzip2MemorySuite extends MemorySuite {
  override protected def compressor: Compressor[IO] = Bzip2Compressor.make[IO]()
  override protected def decompressor: Decompressor[IO] = Bzip2Decompressor.make[IO]()

  // bzip2 is roughly ten times slower than the other codecs, and 32 MiB is still far more than the heap this runs with.
  override protected def megabytes: Int = 32
}
