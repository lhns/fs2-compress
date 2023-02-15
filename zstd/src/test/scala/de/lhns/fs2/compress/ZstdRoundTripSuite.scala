package de.lhns.fs2.compress

import cats.effect.IO
import cats.effect.std.Random
import fs2.{Chunk, Stream}

import java.util
import java.util.zip.ZipEntry

class ZstdRoundTripSuite extends IOSuite {
  test("zstd round trip") {
    for {
      random <- Random.scalaUtilRandom[IO]
      expected <- random.nextBytes(1024 * 1024)
      obtained <- Stream.chunk(Chunk.array(expected))
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