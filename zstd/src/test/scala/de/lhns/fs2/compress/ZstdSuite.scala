package de.lhns.fs2.compress

import cats.effect.IO
import cats.effect.std.Random
import fs2.{Chunk, Stream}
import munit.CatsEffectSuite

import java.nio.charset.StandardCharsets.UTF_8
import java.util
import java.util.Base64

class ZstdSuite extends CatsEffectSuite {
  implicit val zstdCompressor: ZstdCompressor[IO] = ZstdCompressor.make()
  implicit val zstdDecompressor: ZstdDecompressor[IO] = ZstdDecompressor.make()

  test("zstd decompress") {
    val clear = Chunk.array("Hello world!".getBytes(UTF_8))
    val compressed = Chunk.array(
      Base64.getDecoder.decode("KLUv/QRYYQAASGVsbG8gd29ybGQhsn39fw==")
    )
    for {
      obtained <- Stream
        .chunk(compressed)
        .through(ZstdDecompressor[IO].decompress)
        .chunkAll
        .compile
        .lastOrError
    } yield assert(clear == obtained)
  }

  test("zstd round trip") {
    for {
      random <- Random.scalaUtilRandom[IO]
      expected <- random.nextBytes(1024 * 1024)
      obtained <- Stream
        .chunk(Chunk.array(expected))
        .through(ZstdCompressor[IO].compress)
        .through(ZstdDecompressor[IO].decompress)
        .chunkAll
        .compile
        .lastOrError
        .map(_.toArray)
      _ = assert(util.Arrays.equals(expected, obtained))
    } yield ()
  }
}
