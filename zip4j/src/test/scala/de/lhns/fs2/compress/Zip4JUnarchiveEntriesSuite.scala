package de.lhns.fs2.compress

import cats.effect.IO
import fs2.{Chunk, Stream}
import net.lingala.zip4j.model.LocalFileHeader

class Zip4JUnarchiveEntriesSuite extends UnarchiveEntriesSuite[LocalFileHeader] {
  override protected def archive(entries: List[(String, Chunk[Byte])]): IO[Chunk[Byte]] =
    Stream
      .emits(entries)
      .map { case (name, bytes) =>
        (ArchiveEntry[Some, Any](name, Some(bytes.size.toLong)), Stream.chunk[IO, Byte](bytes))
      }
      .through(Zip4JArchiver.make[IO]().archive)
      .compile
      .to(Chunk)

  override protected def unarchiver: Unarchiver[IO, Option, LocalFileHeader] = Zip4JUnarchiver.make[IO]()
}
