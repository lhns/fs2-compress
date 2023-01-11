package de.lhns.fs2.compress

import cats.effect.IO
import cats.effect.std.Random
import fs2.{Chunk, Stream}

import java.util
import java.util.zip.ZipEntry

class RoundTripSuite extends IOSuite {
  test("round trip") {
    for {
      random <- Random.scalaUtilRandom[IO]
      expected <- random.nextBytes(1024 * 1024)
      obtained <- Stream.chunk(Chunk.array(expected))
        .through(ZipSingleFileCompressor[IO](new ZipEntry("test")).compress)
        .through(ZipSingleFileDecompressor[IO]().decompress)
        .through(Bzip2Compressor[IO]().compress)
        .through(Bzip2Decompressor[IO]().decompress)
        .through(GzipCompressor[IO]().compress)
        .through(GzipDecompressor[IO]().decompress)
        .through(ZstdCompressor[IO]().compress)
        .through(ZstdDecompressor[IO]().decompress)
        .chunkAll
        .compile
        .lastOrError
        .map(_.toArray)
      _ = assert(util.Arrays.equals(expected, obtained))
    } yield ()
  }
}
