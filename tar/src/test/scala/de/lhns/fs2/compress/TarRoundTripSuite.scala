package de.lhns.fs2.compress

import cats.effect.IO
import cats.effect.std.Random
import fs2.{Chunk, Stream}

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
}
