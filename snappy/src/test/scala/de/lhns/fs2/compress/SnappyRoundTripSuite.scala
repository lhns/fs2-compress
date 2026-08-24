package de.lhns.fs2.compress

import cats.effect.IO
import cats.effect.std.Random
import fs2.{Chunk, Stream}
import munit.CatsEffectSuite

import java.util

class SnappyRoundTripSuite extends CatsEffectSuite {

  test("snappy unframed round trip") {
    val readMode = SnappyDecompressor.ReadMode.Basic()
    val writeMode = SnappyCompressor.WriteMode.Basic()

    testRoundTrip(readMode, writeMode)
  }

  test("snappy framed round trip") {
    val readMode = SnappyDecompressor.ReadMode.Framed()
    val writeMode = SnappyCompressor.WriteMode.Framed()

    testRoundTrip(readMode, writeMode)
  }

  private def testRoundTrip(readMode: SnappyDecompressor.ReadMode, writeMode: SnappyCompressor.WriteMode): IO[Unit] = {
    implicit val snappyCompressor: SnappyCompressor[IO] = SnappyCompressor.make(mode = writeMode)
    implicit val snappyDecompressor: SnappyDecompressor[IO] = SnappyDecompressor.make(mode = readMode)
    for {
      random <- Random.scalaUtilRandom[IO]
      expected <- random.nextBytes(1024 * 1024)
      obtained <- Stream
        .chunk(Chunk.array(expected))
        .through(SnappyCompressor[IO].compress)
        .through(SnappyDecompressor[IO].decompress)
        .chunkAll
        .compile
        .lastOrError
        .map(_.toArray)
      _ = assert(util.Arrays.equals(expected, obtained))
    } yield ()
  }
}
