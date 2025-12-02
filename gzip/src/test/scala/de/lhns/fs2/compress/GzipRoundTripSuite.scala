package de.lhns.fs2.compress

import cats.effect.IO
import cats.effect.std.Random
import fs2.io.compression._
import fs2.{Chunk, Stream}
import munit.CatsEffectSuite

import java.util

class GzipRoundTripSuite extends CatsEffectSuite {
  test("gzip round trip") {
    for {
      random <- Random.scalaUtilRandom[IO]
      expected <- random.nextBytes(1024 * 1024)
      obtained <- Stream
        .chunk(Chunk.array(expected))
        .through(GzipCompressor.compress[IO]())
        .through(GzipDecompressor.decompress[IO]())
        .chunkAll
        .compile
        .lastOrError
        .map(_.toArray)
      _ = assert(util.Arrays.equals(expected, obtained))
    } yield ()
  }
}
