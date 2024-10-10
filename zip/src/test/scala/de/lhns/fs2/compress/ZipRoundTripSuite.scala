package de.lhns.fs2.compress

import cats.effect.IO
import cats.effect.std.Random
import fs2.{Chunk, Stream}
import munit.CatsEffectSuite

import java.util

class ZipRoundTripSuite extends CatsEffectSuite {
  implicit val zipArchiverSome: ZipArchiver[IO, Some] = ZipArchiver.make()
  implicit val zipArchiver: ZipArchiver[IO, Option] = ZipArchiver.make()
  implicit val zipUnarchiver: ZipUnarchiver[IO] = ZipUnarchiver.make()

  test("zip round trip") {
    for {
      random <- Random.scalaUtilRandom[IO]
      expected <- random.nextBytes(1024 * 1024)
      obtained <- Stream
        .chunk(Chunk.array(expected))
        .through(ArchiveSingleFileCompressor.forName(ZipArchiver[IO, Some], "test", expected.length).compress)
        .through(ArchiveSingleFileDecompressor(ZipUnarchiver[IO]).decompress)
        .chunkAll
        .compile
        .lastOrError
        .map(_.toArray)
      _ = assert(util.Arrays.equals(expected, obtained))
    } yield ()
  }

  test("zip round trip wrong size".fail) {
    for {
      random <- Random.scalaUtilRandom[IO]
      expected <- random.nextBytes(1024 * 1024)
      obtained <- Stream
        .chunk(Chunk.array(expected))
        .through(ArchiveSingleFileCompressor.forName(ZipArchiver[IO, Some], "test", expected.length - 1).compress)
        .through(ArchiveSingleFileDecompressor(ZipUnarchiver[IO]).decompress)
        .chunkAll
        .compile
        .lastOrError
        .map(_.toArray)
      _ = assert(util.Arrays.equals(expected, obtained))
    } yield ()
  }

  test("zip round trip wrong size 2".fail) {
    for {
      random <- Random.scalaUtilRandom[IO]
      expected <- random.nextBytes(1024 * 1024)
      obtained <- Stream
        .chunk(Chunk.array(expected))
        .through(ArchiveSingleFileCompressor.forName(ZipArchiver[IO, Some], "test", expected.length + 1).compress)
        .through(ArchiveSingleFileDecompressor(ZipUnarchiver[IO]).decompress)
        .chunkAll
        .compile
        .lastOrError
        .map(_.toArray)
      _ = assert(util.Arrays.equals(expected, obtained))
    } yield ()
  }

  test("zip round trip without specifying size") {
    for {
      random <- Random.scalaUtilRandom[IO]
      expected <- random.nextBytes(1024 * 1024)
      obtained <- Stream
        .chunk(Chunk.array(expected))
        .through(ArchiveSingleFileCompressor.forName(ZipArchiver[IO, Option], "test").compress)
        .through(ArchiveSingleFileDecompressor(ZipUnarchiver[IO]).decompress)
        .chunkAll
        .compile
        .lastOrError
        .map(_.toArray)
    } yield assert(util.Arrays.equals(expected, obtained))
  }
}
