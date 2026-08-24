package de.lhns.fs2.compress

import cats.effect.IO
import fs2.{Chunk, Stream}

import java.util.zip.ZipEntry

class ZipUnarchiveEntriesSuite extends UnarchiveEntriesSuite[ZipEntry] {
  override protected def archive(entries: List[(String, Chunk[Byte])]): IO[Chunk[Byte]] =
    Stream
      .emits(entries)
      .map { case (name, bytes) => (ArchiveEntry[Option, Any](name), Stream.chunk[IO, Byte](bytes)) }
      .through(ZipArchiver.makeDeflated[IO]().archive)
      .compile
      .to(Chunk)

  override protected def fixtureName: String = "/basic-text.zip"

  override protected def unarchiver: Unarchiver[IO, Option, ZipEntry] = ZipUnarchiver.make[IO]()
}
