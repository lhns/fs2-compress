package de.lhns.fs2.compress

import cats.effect.IO
import fs2.{Chunk, Stream}
import munit.CatsEffectSuite

import java.util.Base64

/** Reads a `.tar.gz` written by GNU tar, which is the composition of an `Unarchiver` and a `Decompressor` that the
  * README describes and that nothing else here exercises.
  */
class TarGzSuite extends CatsEffectSuite {
  implicit val gzipDecompressor: GzipDecompressor[IO] = GzipDecompressor.make()
  implicit val tarUnarchiver: TarUnarchiver[IO] = TarUnarchiver.make()

  test("a .tar.gz written by GNU tar reads back through gunzip and untar") {
    /* Created on an Ubuntu system with:
     * {{{
     *   mkdir tgztempdir
     *   cd tgztempdir
     *   echo -n 'Hello world!' > file1.txt
     *   mkdir -p subdir
     *   echo -n 'Hello from subdir!' > subdir/file2.txt
     *   tar -czO file1.txt subdir/file2.txt | base64; echo
     *   cd ..
     *   rm -rf tgztempdir
     * }}}
     */
    val tgzArchive = Chunk.array(
      Base64.getDecoder
        .decode(
          "H4sIAAAAAAAAA+3UQQqDMBCF4RwlvUCbicZcoddo0YA0RYhKe/wqgVK6qCstwv9tBpIsZpH3Qhsb" +
            "OQ7PQa3HTKqqnKd4Zz5nJqWS0k+HhbeFKCNiK6+0WXGnt7EfLklr1aT29uvd0v1OnZsYO/3oUqwP" +
            "/94F2+vHa92mU5hqwK5VA4v5t/Yr/9Y58r+JnP+QurvOX4EWAAAAAAAAAAAAAAAA2JUX9n5LGQAo" +
            "AAA="
        )
    )

    for {
      obtained <- Stream
        .chunk(tgzArchive)
        .through(GzipDecompressor[IO].decompress)
        .through(TarUnarchiver[IO].unarchive)
        .flatMap { case (archiveEntry, stream) =>
          stream.chunkAll
            .map(content => archiveEntry.name -> new String(content.toArray))
        }
        .chunkAll
        .compile
        .lastOrError
    } yield {
      assert(obtained(0) == ("file1.txt", "Hello world!"))
      assert(obtained(1) == ("subdir/file2.txt", "Hello from subdir!"))
    }
  }
}
