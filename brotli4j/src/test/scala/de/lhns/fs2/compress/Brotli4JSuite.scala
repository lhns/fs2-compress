package de.lhns.fs2.compress

import cats.effect.IO
import cats.effect.std.Random
import fs2.{Chunk, Stream}
import munit.CatsEffectSuite

import java.nio.charset.StandardCharsets.UTF_8
import java.util
import java.util.Base64

class Brotli4JSuite extends CatsEffectSuite {
  implicit val brotliCompressor: Brotli4JCompressor[IO] = Brotli4JCompressor.make()
  implicit val brotliDecompressor: Brotli4JDecompressor[IO] = Brotli4JDecompressor.make()

  test("brotli decompress") {
    val clear = Chunk.array("hello world!".getBytes(UTF_8))
    val compressed = Chunk.array(Base64.getDecoder.decode("iwWAaGVsbG8gd29ybGQhAw=="))
    for {
      obtained <- Stream
        .chunk(compressed)
        .through(Brotli4JDecompressor[IO].decompress)
        .chunkAll
        .compile
        .lastOrError
    } yield assert(clear == obtained)
  }

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
