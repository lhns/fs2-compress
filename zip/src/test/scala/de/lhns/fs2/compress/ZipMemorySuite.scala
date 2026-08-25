package de.lhns.fs2.compress

import cats.effect.IO

class ZipMemorySuite extends MemorySuite {
  override protected def compressor: Compressor[IO] =
    ArchiveSingleFileCompressor.forName[IO](ZipArchiver.makeDeflated[IO](), "test")
  override protected def decompressor: Decompressor[IO] = ArchiveSingleFileDecompressor(ZipUnarchiver.make[IO]())
}
