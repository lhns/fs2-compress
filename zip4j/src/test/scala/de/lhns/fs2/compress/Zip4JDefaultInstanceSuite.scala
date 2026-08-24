package de.lhns.fs2.compress

import cats.effect.IO
import cats.effect.std.Random
import fs2.{Chunk, Stream}
import munit.CatsEffectSuite

import java.util

/** The default instances are usable by importing them, with no `implicit val` at the call site. */
class Zip4JDefaultInstanceSuite extends CatsEffectSuite {
  import Zip4JArchiver.default._
  import Zip4JUnarchiver.default._

  test("round trip through the default instances") {
    for {
      random <- Random.scalaUtilRandom[IO]
      expected <- random.nextBytes(1024)
      obtained <- Stream
        .chunk(Chunk.array(expected))
        .through(ArchiveSingleFileCompressor.forName(Zip4JArchiver[IO], "test", expected.length.toLong).compress)
        .through(ArchiveSingleFileDecompressor(Zip4JUnarchiver[IO]).decompress)
        .chunkAll
        .compile
        .lastOrError
        .map(_.toArray)
      _ = assert(util.Arrays.equals(expected, obtained))
    } yield ()
  }
}
