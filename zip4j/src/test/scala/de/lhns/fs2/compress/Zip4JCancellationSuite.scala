package de.lhns.fs2.compress

import cats.effect.IO
import net.lingala.zip4j.model.LocalFileHeader

class Zip4JCancellationSuite extends ArchiverCancellationSuite[LocalFileHeader] {
  override protected def singleEntryCompressor(name: String, size: Long, chunkSize: Int): Compressor[IO] =
    ArchiveSingleFileCompressor.forName[IO](Zip4JArchiver.make[IO](chunkSize = chunkSize), name, size)

  override protected def unarchiver(chunkSize: Int): Unarchiver[IO, Option, LocalFileHeader] =
    Zip4JUnarchiver.make[IO](chunkSize = chunkSize)
}
