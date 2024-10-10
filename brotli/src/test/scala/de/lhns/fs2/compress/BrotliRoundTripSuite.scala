package de.lhns.fs2.compress

import cats.effect.IO
import cats.effect.std.Random
import fs2.{Chunk, Stream}
import munit.CatsEffectSuite

import java.util

class BrotliRoundTripSuite extends CatsEffectSuite {
  implicit val brotliCompressor: BrotliCompressor[IO] = BrotliCompressor.make()
  implicit val brotliDecompressor: BrotliDecompressor[IO] = BrotliDecompressor.make()

  test("brotli round trip") {
    for {
      random <- Random.scalaUtilRandom[IO]
      expected <- random.nextBytes(1024 * 1024)
      obtained <- Stream
        .chunk(Chunk.array(expected))
        .through(BrotliCompressor[IO].compress)
        .through(BrotliDecompressor[IO].decompress)
        .chunkAll
        .compile
        .lastOrError
        .map(_.toArray)
      _ = assert(util.Arrays.equals(expected, obtained))
    } yield ()
  }
}
