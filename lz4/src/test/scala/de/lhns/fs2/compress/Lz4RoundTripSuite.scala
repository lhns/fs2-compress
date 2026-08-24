package de.lhns.fs2.compress

import cats.effect.IO
import cats.effect.std.Random
import fs2.{Chunk, Stream}
import munit.CatsEffectSuite

import java.util

class Lz4RoundTripSuite extends CatsEffectSuite {
  implicit val lz4Compressor: Lz4Compressor[IO] = Lz4Compressor.make()
  implicit val lz4Decompressor: Lz4Decompressor[IO] = Lz4Decompressor.make()

  test("lz4 round trip") {
    for {
      random <- Random.scalaUtilRandom[IO]
      expected <- random.nextBytes(1024 * 1024)
      obtained <- Stream
        .chunk(Chunk.array(expected))
        .through(Lz4Compressor[IO].compress)
        .through(Lz4Decompressor[IO].decompress)
        .chunkAll
        .compile
        .lastOrError
        .map(_.toArray)
      _ = assert(util.Arrays.equals(expected, obtained))
    } yield ()
  }
}
