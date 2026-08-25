package de.lhns.fs2.compress

import cats.effect.IO

class TarMemorySuite extends MemorySuite {
  override protected def compressor: Compressor[IO] =
    ArchiveSingleFileCompressor.forName[IO](TarArchiver.make[IO](), "test", bytes)
  override protected def decompressor: Decompressor[IO] = ArchiveSingleFileDecompressor(TarUnarchiver.make[IO]())
}
