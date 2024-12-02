package de.lhns.fs2.compress

import cats.effect.IO
import cats.effect.std.Random
import fs2.{Chunk, Stream}
import munit.CatsEffectSuite

import java.nio.charset.StandardCharsets.UTF_8
import java.util
import java.util.Base64

class Lz4Suite extends CatsEffectSuite {
  implicit val lz4Compressor: Lz4Compressor[IO] = Lz4Compressor.make()
  implicit val lz4Decompressor: Lz4Decompressor[IO] = Lz4Decompressor.make()

  test("lz4 decompress") {
    val clear = Chunk.array("Hello world!".getBytes(UTF_8))
    val compressed = Chunk.array(
      Base64.getDecoder.decode("BCJNGGRApwwAAIBIZWxsbyB3b3JsZCEAAAAAcXerxA==")
    )
    for {
      obtained <- Stream
        .chunk(compressed)
        .through(Lz4Decompressor[IO].decompress)
        .chunkAll
        .compile
        .lastOrError
    } yield assert(clear == obtained)
  }

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
