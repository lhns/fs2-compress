package de.lhns.fs2.compress

import java.util
import java.util.zip.Deflater

import cats.effect.IO
import fs2.{Chunk, Stream}
import munit.CatsEffectSuite

/** Choosing how hard DEFLATED entries are compressed.
  *
  * The payload repeats a single byte, so any level above `Deflater.NO_COMPRESSION` shrinks it to almost nothing, while
  * `NO_COMPRESSION` writes it out in full plus the framing of the deflate blocks.
  */
class ZipDeflateLevelSuite extends CatsEffectSuite {
  implicit val zipUnarchiver: ZipUnarchiver[IO] = ZipUnarchiver.make()

  private val payload = Array.fill[Byte](64 * 1024)(42)

  private def archive(archiver: ZipArchiver[IO, Option]): IO[Chunk[Byte]] =
    Stream
      .emit(ArchiveEntry[Option, Any]("test") -> Stream.chunk[IO, Byte](Chunk.array(payload)))
      .through(archiver.archive)
      .compile
      .to(Chunk)

  private def unarchive(archived: Chunk[Byte]): IO[Array[Byte]] =
    Stream
      .chunk(archived)
      .through(ZipUnarchiver[IO].unarchive)
      .flatMap { case (_, data) => data }
      .compile
      .to(Array)

  test("NO_COMPRESSION writes the data out in full, and it round trips") {
    for {
      archived <- archive(ZipArchiver.makeDeflated[IO](deflateLevel = Some(Deflater.NO_COMPRESSION)))
      _ = assert(archived.size > payload.length, s"the archive is ${archived.size} bytes, so it was compressed")
      obtained <- unarchive(archived)
      _ = assert(util.Arrays.equals(obtained, payload))
    } yield ()
  }

  test("leaving the level out compresses") {
    archive(ZipArchiver.makeDeflated[IO]()).map { archived =>
      assert(archived.size < payload.length / 10, s"the archive is ${archived.size} bytes, so it was not compressed")
    }
  }
}
