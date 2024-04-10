package de.lhns.fs2.compress

import cats.effect.IO
import cats.effect.std.Random
import fs2.{Chunk, Stream}

import java.util

class ZipRoundTripSuite extends IOSuite {
  implicit val zipArchiver: ZipArchiver[IO] = ZipArchiver.make()
  implicit val zipUnarchiver: ZipUnarchiver[IO] = ZipUnarchiver.make()

  test("zip round trip") {
    for {
      random <- Random.scalaUtilRandom[IO]
      expected <- random.nextBytes(1024 * 1024)
      obtained <- Stream.chunk(Chunk.array(expected))
        .through(ArchiveSingleFileCompressor.forName(ZipArchiver[IO], "test", expected.length).compress)
        .through(ArchiveSingleFileDecompressor(ZipUnarchiver[IO]).decompress)
        .chunkAll
        .compile
        .lastOrError
        .map(_.toArray)
      _ = assert(util.Arrays.equals(expected, obtained))
    } yield ()
  }
}
