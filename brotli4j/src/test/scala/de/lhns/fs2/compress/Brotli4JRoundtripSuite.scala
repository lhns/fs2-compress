package de.lhns.fs2.compress

import cats.effect.IO
import cats.effect.std.Random
import fs2.{Chunk, Stream}
import munit.CatsEffectSuite

import java.util

class Brotli4JRoundtripSuite extends CatsEffectSuite {
  implicit val brotliCompressor: Brotli4JCompressor[IO] = Brotli4JCompressor.make()
  implicit val brotliDecompressor: Brotli4JDecompressor[IO] = Brotli4JDecompressor.make()

  test("brotli round trip") {
    for {
      random <- Random.scalaUtilRandom[IO]
      expected <- random.nextBytes(1024 * 1024)
      obtained <- Stream
        .chunk(Chunk.array(expected))
        .through(Brotli4JCompressor[IO].compress)
        .through(Brotli4JDecompressor[IO].decompress)
        .chunkAll
        .compile
        .lastOrError
        .map(_.toArray)
      _ = assert(util.Arrays.equals(expected, obtained))
    } yield ()
  }
}
