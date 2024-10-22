package de.lhns.fs2.compress

import cats.effect.IO
import cats.effect.std.Random
import fs2.{Chunk, Stream}
import munit.CatsEffectSuite

import java.util
import java.util.Base64

class ZipSuite extends CatsEffectSuite {
  implicit val zipArchiverSome: ZipArchiver[IO, Some] = ZipArchiver.make()
  implicit val zipArchiver: ZipArchiver[IO, Option] = ZipArchiver.make()
  implicit val zipUnarchiver: ZipUnarchiver[IO] = ZipUnarchiver.make()

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
        .through(ZipUnarchiver[IO].unarchive)
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

  test("zip round trip") {
    for {
      random <- Random.scalaUtilRandom[IO]
      expected <- random.nextBytes(1024 * 1024)
      obtained <- Stream
        .chunk(Chunk.array(expected))
        .through(ArchiveSingleFileCompressor.forName(ZipArchiver[IO, Some], "test", expected.length).compress)
        .through(ArchiveSingleFileDecompressor(ZipUnarchiver[IO]).decompress)
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
        .through(ArchiveSingleFileCompressor.forName(ZipArchiver[IO, Some], "test", expected.length - 1).compress)
        .through(ArchiveSingleFileDecompressor(ZipUnarchiver[IO]).decompress)
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
        .through(ArchiveSingleFileCompressor.forName(ZipArchiver[IO, Some], "test", expected.length + 1).compress)
        .through(ArchiveSingleFileDecompressor(ZipUnarchiver[IO]).decompress)
        .chunkAll
        .compile
        .lastOrError
        .map(_.toArray)
      _ = assert(util.Arrays.equals(expected, obtained))
    } yield ()
  }

  test("zip round trip without specifying size") {
    for {
      random <- Random.scalaUtilRandom[IO]
      expected <- random.nextBytes(1024 * 1024)
      obtained <- Stream
        .chunk(Chunk.array(expected))
        .through(ArchiveSingleFileCompressor.forName(ZipArchiver[IO, Option], "test").compress)
        .through(ArchiveSingleFileDecompressor(ZipUnarchiver[IO]).decompress)
        .chunkAll
        .compile
        .lastOrError
        .map(_.toArray)
    } yield assert(util.Arrays.equals(expected, obtained))
  }
}
