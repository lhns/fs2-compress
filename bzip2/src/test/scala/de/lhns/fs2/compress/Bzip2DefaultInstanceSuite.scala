package de.lhns.fs2.compress

import cats.effect.IO
import cats.effect.std.Random
import fs2.{Chunk, Stream}
import munit.CatsEffectSuite

import java.util

/** The default instances are usable by importing them, with no `implicit val` at the call site. */
class Bzip2DefaultInstanceSuite extends CatsEffectSuite {
  import Bzip2Compressor.default._
  import Bzip2Decompressor.default._

  test("round trip through the default instances") {
    for {
      random <- Random.scalaUtilRandom[IO]
      expected <- random.nextBytes(1024)
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
