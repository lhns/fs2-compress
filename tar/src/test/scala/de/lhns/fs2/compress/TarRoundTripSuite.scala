package de.lhns.fs2.compress

import cats.effect.IO
import cats.effect.std.Random
import de.lhns.fs2.compress.Tar._
import fs2.{Chunk, Stream}
import org.apache.commons.compress.archivers.tar.TarArchiveEntry

import java.util

class TarRoundTripSuite extends IOSuite {
  implicit val tarArchiver: TarArchiver[IO] = TarArchiver.make()
  implicit val tarUnarchiver: TarUnarchiver[IO] = TarUnarchiver.make()

  test("tar round trip") {
    for {
      random <- Random.scalaUtilRandom[IO]
      expected <- random.nextBytes(1024 * 1024)
      obtained <- Stream.chunk(Chunk.array(expected))
        .through(ArchiveSingleFileCompressor.forName(TarArchiver[IO], "test", expected.length).compress)
        .through(ArchiveSingleFileDecompressor(TarUnarchiver[IO]).decompress)
        .chunkAll
        .compile
        .lastOrError
        .map(_.toArray)
      _ = assert(util.Arrays.equals(expected, obtained))
    } yield ()
  }

  test("tar round trip wrong size".fail) {
    for {
      random <- Random.scalaUtilRandom[IO]
      expected <- random.nextBytes(1024 * 1024)
      obtained <- Stream.chunk(Chunk.array(expected))
        .through(ArchiveSingleFileCompressor.forName(TarArchiver[IO], "test", expected.length - 1).compress)
        .through(ArchiveSingleFileDecompressor(TarUnarchiver[IO]).decompress)
        .chunkAll
        .compile
        .lastOrError
        .map(_.toArray)
      _ = assert(util.Arrays.equals(expected, obtained))
    } yield ()
  }

  test("tar round trip wrong size 2".fail) {
    for {
      random <- Random.scalaUtilRandom[IO]
      expected <- random.nextBytes(1024 * 1024)
      obtained <- Stream.chunk(Chunk.array(expected))
        .through(ArchiveSingleFileCompressor.forName(TarArchiver[IO], "test", expected.length + 1).compress)
        .through(ArchiveSingleFileDecompressor(TarUnarchiver[IO]).decompress)
        .chunkAll
        .compile
        .lastOrError
        .map(_.toArray)
      _ = assert(util.Arrays.equals(expected, obtained))
    } yield ()
  }

  test("copy tar entry") {
    val entry = ArchiveEntry(name = "test")
    val tarEntry = entry.withUnderlying(entry.underlying[TarArchiveEntry])
    val tarEntry2 = tarEntry.withName("test2")
    val tarArchiveEntry = tarEntry2.underlying[TarArchiveEntry] // underlying TarArchiveEntry entry is copied
    assertEquals(tarArchiveEntry.getName, "test2")
  }
}
