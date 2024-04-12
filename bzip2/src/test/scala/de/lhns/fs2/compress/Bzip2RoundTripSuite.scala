package de.lhns.fs2.compress

import cats.effect.IO
import cats.effect.std.Random
import fs2.{Chunk, Stream}

import java.util

class Bzip2RoundTripSuite extends IOSuite {
  implicit val bzip2Compressor: Bzip2Compressor[IO] = Bzip2Compressor.make()
  implicit val bzip2Decompressor: Bzip2Decompressor[IO] = Bzip2Decompressor.make()

  test("bzip2 round trip") {
    for {
      random <- Random.scalaUtilRandom[IO]
      expected <- random.nextBytes(1024 * 1024)
      obtained <- Stream
        .chunk(Chunk.array(expected))
        .through(Bzip2Compressor[IO].compress)
        .through(Bzip2Decompressor[IO].decompress)
        .chunkAll
        .compile
        .lastOrError
        .map(_.toArray)
      _ = assert(util.Arrays.equals(expected, obtained))
    } yield ()
  }
}
