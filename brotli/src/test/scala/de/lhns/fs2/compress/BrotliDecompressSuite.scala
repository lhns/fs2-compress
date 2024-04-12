package de.lhns.fs2.compress

import cats.effect.IO
import fs2._

import java.nio.charset.StandardCharsets
import java.util
import java.util.Base64

class BrotliDecompressSuite extends IOSuite {
  implicit val brotliDecompressor: BrotliDecompressor[IO] = BrotliDecompressor.make()

  test("brotli decompress") {
    val expected = "hello world!".getBytes(StandardCharsets.UTF_8)
    val compressedBase64 = "iwWAaGVsbG8gd29ybGQhAw=="
    val compressed = Base64.getDecoder.decode(compressedBase64)
    for {
      obtained <- Stream
        .chunk(Chunk.array(compressed))
        .through(BrotliDecompressor[IO].decompress)
        .chunkAll
        .compile
        .lastOrError
        .map(_.toArray)
      _ = assert(util.Arrays.equals(expected, obtained))
    } yield ()
  }
}
