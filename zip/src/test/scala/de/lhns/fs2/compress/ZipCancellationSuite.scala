package de.lhns.fs2.compress

import cats.effect.IO

import java.util.zip.ZipEntry

class ZipCancellationSuite extends ArchiverCancellationSuite[ZipEntry] {
  override protected def singleEntryCompressor(name: String, size: Long, chunkSize: Int): Compressor[IO] =
    ArchiveSingleFileCompressor.forName[IO](ZipArchiver.makeDeflated[IO](chunkSize), name)

  override protected def unarchiver(chunkSize: Int): Unarchiver[IO, Option, ZipEntry] =
    ZipUnarchiver.make[IO](chunkSize)
}
