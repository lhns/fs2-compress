package de.lhns.fs2.compress

import cats.effect.IO
import cats.effect.std.Random
import fs2.io.compression._
import fs2.{Chunk, Stream}
import munit.CatsEffectSuite

import java.nio.charset.StandardCharsets.UTF_8
import java.util
import java.util.Base64

class GzipSuite extends CatsEffectSuite {
  implicit val gzipCompressor: GzipCompressor[IO] = GzipCompressor.make()
  implicit val gzipDecompressor: GzipDecompressor[IO] = GzipDecompressor.make()

  test("gzip decompress") {
    val clear = Chunk.array("Hello world!".getBytes(UTF_8))
    val compressed = Chunk.array(
      Base64.getDecoder.decode("H4sIAMKVBmcAA/NIzcnJVyjPL8pJUQQAlRmFGwwAAAA=")
    )
    for {
      obtained <- Stream
        .chunk(compressed)
        .through(GzipDecompressor[IO].decompress)
        .chunkAll
        .compile
        .lastOrError
    } yield assert(clear == obtained)
  }

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
