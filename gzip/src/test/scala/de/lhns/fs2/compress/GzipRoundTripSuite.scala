package de.lhns.fs2.compress

import cats.effect.IO
import cats.effect.std.Random
import fs2.io.compression._
import fs2.{Chunk, Stream}
import munit.CatsEffectSuite

import java.util

class GzipRoundTripSuite extends CatsEffectSuite {
  implicit val gzipCompressor: GzipCompressor[IO] = GzipCompressor.make()
  implicit val gzipDecompressor: GzipDecompressor[IO] = GzipDecompressor.make()

  test("gzip round trip") {
    for {
      random <- Random.scalaUtilRandom[IO]
      expected <- random.nextBytes(1024 * 1024)
      obtained <- Stream
        .chunk(Chunk.array(expected))
        .through(GzipCompressor[IO].compress)
        .through(GzipDecompressor[IO].decompress)
        .chunkAll
        .compile
        .lastOrError
        .map(_.toArray)
      _ = assert(util.Arrays.equals(expected, obtained))
    } yield ()
  }
}
