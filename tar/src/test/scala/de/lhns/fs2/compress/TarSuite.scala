package de.lhns.fs2.compress

import cats.effect.IO
import cats.effect.std.Random
import de.lhns.fs2.compress.Tar._
import fs2.{Chunk, Stream}
import munit.CatsEffectSuite
import org.apache.commons.compress.archivers.tar.TarArchiveEntry

import java.util
import java.util.Base64

class TarSuite extends CatsEffectSuite {
  implicit val tarArchiver: TarArchiver[IO] = TarArchiver.make()
  implicit val tarUnarchiver: TarUnarchiver[IO] = TarUnarchiver.make()
  implicit val gzipDecompressor: GzipDecompressor[IO] = GzipDecompressor.make()

  test("tar unarchive") {
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
          stream
            .chunkAll
            .map(content => archiveEntry.name -> new String(content.toArray))
        }
        .chunkAll
        .compile
        .lastOrError
    } yield {
      assert(obtained.head == ("file1.txt", "Hello world!"))
      assert(obtained(1) == ("subdir/file2.txt", "Hello from subdir!"))
    }
  }

  test("tar round trip") {
    for {
      random <- Random.scalaUtilRandom[IO]
      expected <- random.nextBytes(1024 * 1024)
      obtained <- Stream
        .chunk(Chunk.array(expected))
        .through(ArchiveSingleFileCompressor.forName(TarArchiver[IO], "test", expected.length).compress)
        .through(ArchiveSingleFileDecompressor(TarUnarchiver[IO]).decompress)
        .chunkAll
        .compile
        .lastOrError
        .map(_.toArray)
      _ = assert(util.Arrays.equals(expected, obtained))
    } yield ()
  }

  test("tar round trip wrong size".fail) {
    for {
      random <- Random.scalaUtilRandom[IO]
      expected <- random.nextBytes(1024 * 1024)
      obtained <- Stream
        .chunk(Chunk.array(expected))
        .through(ArchiveSingleFileCompressor.forName(TarArchiver[IO], "test", expected.length - 1).compress)
        .through(ArchiveSingleFileDecompressor(TarUnarchiver[IO]).decompress)
        .chunkAll
        .compile
        .lastOrError
        .map(_.toArray)
      _ = assert(util.Arrays.equals(expected, obtained))
    } yield ()
  }

  test("tar round trip wrong size 2".fail) {
    for {
      random <- Random.scalaUtilRandom[IO]
      expected <- random.nextBytes(1024 * 1024)
      obtained <- Stream
        .chunk(Chunk.array(expected))
        .through(ArchiveSingleFileCompressor.forName(TarArchiver[IO], "test", expected.length + 1).compress)
        .through(ArchiveSingleFileDecompressor(TarUnarchiver[IO]).decompress)
        .chunkAll
        .compile
        .lastOrError
        .map(_.toArray)
      _ = assert(util.Arrays.equals(expected, obtained))
    } yield ()
  }

  test("copy tar entry") {
    val entry = ArchiveEntry(name = "test")
    val tarEntry = entry.withUnderlying(entry.underlying[TarArchiveEntry])
    val tarEntry2 = tarEntry.withName("test2")
    val tarArchiveEntry = tarEntry2.underlying[TarArchiveEntry] // underlying TarArchiveEntry entry is copied
    assertEquals(tarArchiveEntry.getName, "test2")
  }
}
