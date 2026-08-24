package de.lhns.fs2.compress

import cats.effect.IO
import cats.effect.std.Random
import fs2.{Chunk, Stream}
import munit.CatsEffectSuite

import java.util

class Zip4JRoundTripSuite extends CatsEffectSuite {
  implicit val zipArchiver: Zip4JArchiver[IO] = Zip4JArchiver.make()
  implicit val zipUnarchiver: Zip4JUnarchiver[IO] = Zip4JUnarchiver.make()

  test("zip round trip") {
    for {
      random <- Random.scalaUtilRandom[IO]
      expected <- random.nextBytes(1024 * 1024)
      obtained <- Stream
        .chunk(Chunk.array(expected))
        .through(ArchiveSingleFileCompressor.forName(Zip4JArchiver[IO], "test", expected.length).compress)
        .through(ArchiveSingleFileDecompressor(Zip4JUnarchiver[IO]).decompress)
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
        .through(ArchiveSingleFileCompressor.forName(Zip4JArchiver[IO], "test", expected.length - 1).compress)
        .through(ArchiveSingleFileDecompressor(Zip4JUnarchiver[IO]).decompress)
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
        .through(ArchiveSingleFileCompressor.forName(Zip4JArchiver[IO], "test", expected.length + 1).compress)
        .through(ArchiveSingleFileDecompressor(Zip4JUnarchiver[IO]).decompress)
        .chunkAll
        .compile
        .lastOrError
        .map(_.toArray)
      _ = assert(util.Arrays.equals(expected, obtained))
    } yield ()
  }
}
