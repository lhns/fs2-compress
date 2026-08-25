package de.lhns.fs2.compress

import cats.effect.IO

class Zip4JMemorySuite extends MemorySuite {
  override protected def compressor: Compressor[IO] =
    ArchiveSingleFileCompressor.forName[IO](Zip4JArchiver.make[IO](), "test", bytes)
  override protected def decompressor: Decompressor[IO] = ArchiveSingleFileDecompressor(Zip4JUnarchiver.make[IO]())
}
