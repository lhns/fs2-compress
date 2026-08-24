package de.lhns.fs2.compress

import cats.effect.IO
import org.apache.commons.compress.archivers.tar.TarArchiveEntry

class TarCancellationSuite extends ArchiverCancellationSuite[TarArchiveEntry] {
  override protected def singleEntryCompressor(name: String, size: Long, chunkSize: Int): Compressor[IO] =
    ArchiveSingleFileCompressor.forName[IO](TarArchiver.make[IO](chunkSize), name, size)

  override protected def unarchiver(chunkSize: Int): Unarchiver[IO, Option, TarArchiveEntry] =
    TarUnarchiver.make[IO](chunkSize)
}
