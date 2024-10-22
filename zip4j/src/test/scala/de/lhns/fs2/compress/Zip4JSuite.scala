package de.lhns.fs2.compress

import cats.effect.IO
import cats.effect.std.Random
import fs2.{Chunk, Stream}
import munit.CatsEffectSuite

import java.util
import java.util.Base64

class Zip4JSuite extends CatsEffectSuite {
  implicit val zipArchiver: Zip4JArchiver[IO] = Zip4JArchiver.make()
  implicit val zipUnarchiver: Zip4JUnarchiver[IO] = Zip4JUnarchiver.make()

  test("zip unarchive") {
    /* Created on an Ubuntu system with:
     * {{{
     *   mkdir ziptempdir
     *   cd ziptempdir
     *   echo -n 'Hello world!' > file1.txt
     *   mkdir -p subdir
     *   echo -n 'Hello from subdir!' > subdir/file2.txt
     *   zip - file1.txt subdir/file2.txt | base64; echo
     *   cd ..
     *   rm -rf ziptempdir
     * }}}
     */
    val zipArchive = Chunk.array(
      Base64.getDecoder
        .decode(
          "UEsDBBQACAAIAC11SlkAAAAAAAAAAAwAAAAJABwAZmlsZTEudHh0VVQJAAN2ywdndssHZ3V4CwAB" +
            "BOgDAAAE6AMAAPNIzcnJVyjPL8pJUQQAUEsHCJUZhRsOAAAADAAAAFBLAwQUAAgACAAtdUpZAAAA" +
            "AAAAAAASAAAAEAAcAHN1YmRpci9maWxlMi50eHRVVAkAA3bLB2d2ywdndXgLAAEE6AMAAAToAwAA" +
            "80jNyclXSCvKz1UoLk1KySxSBABQSwcIe/g7bxQAAAASAAAAUEsBAh4DFAAIAAgALXVKWZUZhRsO" +
            "AAAADAAAAAkAGAAAAAAAAQAAALSBAAAAAGZpbGUxLnR4dFVUBQADdssHZ3V4CwABBOgDAAAE6AMA" +
            "AFBLAQIeAxQACAAIAC11Sll7+DtvFAAAABIAAAAQABgAAAAAAAEAAAC0gWEAAABzdWJkaXIvZmls" +
            "ZTIudHh0VVQFAAN2ywdndXgLAAEE6AMAAAToAwAAUEsFBgAAAAACAAIApQAAAM8AAAAAAA=="
        )
    )
    for {
      obtained <- Stream
        .chunk(zipArchive)
        .through(Zip4JUnarchiver[IO].unarchive)
        .flatMap { case (archiveEntry, stream) =>
          stream.chunkAll
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

  test("zip unarchive with password") {
    /* Created as above, with password "secret" (using the `-e` option) */
    val encryptedZipArchive = Chunk.array(
      Base64.getDecoder
        .decode(
          "UEsDBBQACQAIACB9SlkAACB9AAAAAAwAAAAJABwAZmlsZTEudHh0VVQJAANr2Qdna9kHZ3V4CwAB" +
            "BOgDAAAE6AMAAKX+yuoRx1uZ+JfBFduDbdvt4DTyA9cqw97vUEsHCJUZhRsaAAAADAAAAFBLAwQU" +
            "AAkACAAgfUpZAAAgfQAAAAASAAAAEAAcAHN1YmRpci9maWxlMi50eHRVVAkAA2vZB2dr2QdndXgL" +
            "AAEE6AMAAAToAwAA4KYKUQzabVa51KZI/KUfUqTQz6Ulq2bWx/xEQ7tF6hlQSwcIe/g7byAAAAAS" +
            "AAAAUEsBAh4DFAAJAAgAIH1KWZUZhRsaAAAADAAAAAkAGAAAAAAAAQAAALSBAAAAAGZpbGUxLnR4" +
            "dFVUBQADa9kHZ3V4CwABBOgDAAAE6AMAAFBLAQIeAxQACQAIACB9Sll7+DtvIAAAABIAAAAQABgA" +
            "AAAAAAEAAAC0gW0AAABzdWJkaXIvZmlsZTIudHh0VVQFAANr2QdndXgLAAEE6AMAAAToAwAAUEsF" +
            "BgAAAAACAAIApQAAAOcAAAAAAA=="
        )
    )
    for {
      obtained <- Stream
        .chunk(encryptedZipArchive)
        .through(Zip4JUnarchiver.make[IO](password = Some("secret")).unarchive)
        .flatMap { case (archiveEntry, stream) =>
          stream.chunkAll
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

  test("zip round trip") {
    for {
      random <- Random.scalaUtilRandom[IO]
      expected <- random.nextBytes(1024 * 1024)
      obtained <- Stream
        .chunk(Chunk.array(expected))
        .through(ArchiveSingleFileCompressor.forName(Zip4JArchiver[IO], "test", expected.length).compress)
        .through(ArchiveSingleFileDecompressor(Zip4JUnarchiver[IO]).decompress)
        .chunkAll
        .compile
        .lastOrError
        .map(_.toArray)
      _ = assert(util.Arrays.equals(expected, obtained))
    } yield ()
  }

  test("zip round trip wrong size".fail) {
    for {
      random <- Random.scalaUtilRandom[IO]
      expected <- random.nextBytes(1024 * 1024)
      obtained <- Stream
        .chunk(Chunk.array(expected))
        .through(ArchiveSingleFileCompressor.forName(Zip4JArchiver[IO], "test", expected.length - 1).compress)
        .through(ArchiveSingleFileDecompressor(Zip4JUnarchiver[IO]).decompress)
        .chunkAll
        .compile
        .lastOrError
        .map(_.toArray)
      _ = assert(util.Arrays.equals(expected, obtained))
    } yield ()
  }

  test("zip round trip wrong size 2".fail) {
    for {
      random <- Random.scalaUtilRandom[IO]
      expected <- random.nextBytes(1024 * 1024)
      obtained <- Stream
        .chunk(Chunk.array(expected))
        .through(ArchiveSingleFileCompressor.forName(Zip4JArchiver[IO], "test", expected.length + 1).compress)
        .through(ArchiveSingleFileDecompressor(Zip4JUnarchiver[IO]).decompress)
        .chunkAll
        .compile
        .lastOrError
        .map(_.toArray)
      _ = assert(util.Arrays.equals(expected, obtained))
    } yield ()
  }
}
