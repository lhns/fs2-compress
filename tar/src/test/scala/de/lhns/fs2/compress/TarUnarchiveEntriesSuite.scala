package de.lhns.fs2.compress

import cats.effect.IO
import fs2.{Chunk, Stream}
import org.apache.commons.compress.archivers.tar.TarArchiveEntry

class TarUnarchiveEntriesSuite extends UnarchiveEntriesSuite[TarArchiveEntry] {
  override protected def archive(entries: List[(String, Chunk[Byte])]): IO[Chunk[Byte]] =
    Stream
      .emits(entries)
      .map { case (name, bytes) =>
        (ArchiveEntry[Some, Any](name, Some(bytes.size.toLong)), Stream.chunk[IO, Byte](bytes))
      }
      .through(TarArchiver.make[IO]().archive)
      .compile
      .to(Chunk)

  override protected def fixtureName: String = "/basic-text.tar"

  override protected def unarchiver: Unarchiver[IO, Option, TarArchiveEntry] = TarUnarchiver.make[IO]()
}
