package de.lhns.fs2.compress

import cats.effect.IO
import cats.effect.std.Random
import fs2.{Chunk, Stream}
import munit.CatsEffectSuite

import java.nio.charset.StandardCharsets.UTF_8
import java.util
import java.util.Base64

class Bzip2Suite extends CatsEffectSuite {
  implicit val bzip2Compressor: Bzip2Compressor[IO] = Bzip2Compressor.make()
  implicit val bzip2Decompressor: Bzip2Decompressor[IO] = Bzip2Decompressor.make()

  private val clear = Chunk.array("Hello world!".getBytes(UTF_8))
  private val compressed = Chunk.array(
    Base64.getDecoder.decode("QlpoOTFBWSZTWQNY9XcAAAEVgGAAAEAGBJCAIAAxBkxBA0wi4Itio54u5IpwoSAGseru")
  )

  test("bzip2 compress") {
    for {
      obtained <- Stream
        .chunk(clear)
        .through(Bzip2Compressor[IO].compress)
        .chunkAll
        .compile
        .lastOrError
    } yield assert(compressed == obtained)
  }

  test("bzip2 decompress") {
    for {
      obtained <- Stream
        .chunk(compressed)
        .through(Bzip2Decompressor[IO].decompress)
        .chunkAll
        .compile
        .lastOrError
    } yield assert(clear == obtained)
  }

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
