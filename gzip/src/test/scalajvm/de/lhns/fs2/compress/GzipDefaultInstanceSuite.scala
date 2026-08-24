package de.lhns.fs2.compress

import cats.effect.IO
import cats.effect.std.Random
import fs2.io.compression._
import fs2.{Chunk, Stream}
import munit.CatsEffectSuite

import java.util

/** The default instances are usable by importing them, with no `implicit val` at the call site. */
class GzipDefaultInstanceSuite extends CatsEffectSuite {
  import GzipCompressor.default._
  import GzipDecompressor.default._

  test("gzip round trip through the default instances") {
    for {
      random <- Random.scalaUtilRandom[IO]
      expected <- random.nextBytes(1024)
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

  test("a default instance satisfies an abstract Compressor request") {
    def compressWith[F[_]](stream: Stream[F, Byte])(implicit compressor: Compressor[F]): Stream[F, Byte] =
      stream.through(compressor.compress)

    compressWith(Stream.chunk[IO, Byte](Chunk.array("hello".getBytes))).compile.drain.map(_ => ())
  }
}
