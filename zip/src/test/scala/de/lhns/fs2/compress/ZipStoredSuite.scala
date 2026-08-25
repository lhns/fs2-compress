package de.lhns.fs2.compress

import cats.effect.IO
import cats.effect.std.Random
import de.lhns.fs2.compress.Zip._
import fs2.{Chunk, Stream}
import munit.CatsEffectSuite

import java.io.ByteArrayInputStream
import java.util
import java.util.zip.{CRC32, ZipEntry, ZipInputStream, ZipOutputStream}

/** Writing entries uncompressed.
  *
  * The zip header for a stored entry carries the CRC of the data and comes before it, so the caller has to work the CRC
  * out in advance and hand it over. These tests cover doing that, what happens when it is missing, and what an archiver
  * makes of an entry that arrives carrying another archive's method and sizes.
  */
class ZipStoredSuite extends CatsEffectSuite {
  implicit val zipUnarchiver: ZipUnarchiver[IO] = ZipUnarchiver.make()

  private val archiver = ZipArchiver.makeStored[IO]()

  private def crcOf(bytes: Array[Byte]): Long = {
    val crc = new CRC32
    crc.update(bytes)
    crc.getValue
  }

  private def storedEntry(name: String, bytes: Array[Byte]): ArchiveEntry[Some, ZipEntry] =
    ArchiveEntry[Some, Any](name, Some(bytes.length.toLong)).withCrc(crcOf(bytes))

  private def methodsOf(archived: Chunk[Byte]): IO[List[Int]] = IO.blocking {
    // Read the archive with a plain ZipInputStream, so that the method is read out of the file itself rather than
    // through this library's own conversion. Without this the tests would still pass if the archiver quietly fell
    // back to DEFLATED.
    val in = new ZipInputStream(new ByteArrayInputStream(archived.toArray))
    try Iterator.continually(in.getNextEntry).takeWhile(_ != null).map(_.getMethod).toList
    finally in.close()
  }

  test("an entry with a supplied CRC round trips, and is really stored") {
    for {
      random <- Random.scalaUtilRandom[IO]
      expected <- random.nextBytes(64 * 1024)
      archived <- Stream
        .emit(storedEntry("test", expected) -> Stream.chunk[IO, Byte](Chunk.array(expected)))
        .through(archiver.archive)
        .compile
        .to(Chunk)
      methods <- methodsOf(archived)
      _ = assertEquals(methods, List(ZipOutputStream.STORED))
      obtained <- Stream
        .chunk(archived)
        .through(ZipUnarchiver[IO].unarchive)
        .flatMap { case (_, data) => data }
        .chunkAll
        .compile
        .lastOrError
      _ = assert(util.Arrays.equals(obtained.toArray, expected))
    } yield ()
  }

  test("an entry without a CRC is reported before anything is written") {
    val bytes = "hello".getBytes
    val entry = ArchiveEntry[Some, Any]("test", Some(bytes.length.toLong))
    Stream
      .emit(entry -> Stream.chunk[IO, Byte](Chunk.array(bytes)))
      .through(archiver.archive)
      .compile
      .drain
      .attempt
      .map {
        case Left(e: IllegalArgumentException) =>
          assert(e.getMessage.contains("test"), s"the message should name the entry, got: ${e.getMessage}")
        case Left(other) => fail(s"expected an IllegalArgumentException but got $other")
        case Right(_) => fail("writing a stored entry with no CRC was allowed to succeed")
      }
  }

  test("the CRC survives a rename, since renaming does not change the data") {
    val bytes = "hello".getBytes
    val entry = ArchiveEntry[Some, Any]("test", Some(bytes.length.toLong)).withCrc(crcOf(bytes)).withName("other")

    for {
      archived <- Stream
        .emit(entry -> Stream.chunk[IO, Byte](Chunk.array(bytes)))
        .through(archiver.archive)
        .compile
        .to(Chunk)
      methods <- methodsOf(archived)
      _ = assertEquals(methods, List(ZipOutputStream.STORED))
      names <- Stream
        .chunk(archived)
        .through(ZipUnarchiver[IO].unarchive)
        .map { case (entry, _) => entry.name }
        .compile
        .toList
      _ = assertEquals(names, List("other"))
    } yield ()
  }

  test("an entry carrying another archive's method and compressed size is still written stored") {
    val bytes = "hello".getBytes
    // What an entry read out of a deflated archive looks like: it brings that archive's method and compressed size
    // with it. Taken as they are, the entry would be written deflated, with a compressed size describing data this
    // archive is not writing.
    val zipEntry = new ZipEntry("test")
    zipEntry.setMethod(ZipOutputStream.DEFLATED)
    zipEntry.setSize(bytes.length.toLong)
    zipEntry.setCompressedSize(2L)
    zipEntry.setCrc(crcOf(bytes))
    val entry = ArchiveEntry[Some, Any]("test", Some(bytes.length.toLong)).withUnderlying(zipEntry)

    for {
      archived <- Stream
        .emit(entry -> Stream.chunk[IO, Byte](Chunk.array(bytes)))
        .through(archiver.archive)
        .compile
        .to(Chunk)
      methods <- methodsOf(archived)
      _ = assertEquals(methods, List(ZipOutputStream.STORED))
      obtained <- Stream
        .chunk(archived)
        .through(ZipUnarchiver[IO].unarchive)
        .flatMap { case (_, data) => data }
        .chunkAll
        .compile
        .lastOrError
      _ = assert(util.Arrays.equals(obtained.toArray, bytes))
    } yield ()
  }

  test("a deflated archiver ignores a compressed size belonging to another archive") {
    val bytes = ("hello world " * 100).getBytes
    // Deflating produces a compressed size of its own. Taking the one the entry arrived with, writing rejects the
    // entry outright: "invalid entry compressed size".
    val zipEntry = new ZipEntry("test")
    zipEntry.setMethod(ZipOutputStream.DEFLATED)
    zipEntry.setSize(bytes.length.toLong)
    zipEntry.setCompressedSize(2L)
    zipEntry.setCrc(crcOf(bytes))
    val entry = ArchiveEntry[Some, Any]("test", Some(bytes.length.toLong)).withUnderlying(zipEntry)

    for {
      archived <- Stream
        .emit(entry -> Stream.chunk[IO, Byte](Chunk.array(bytes)))
        .through(ZipArchiver.makeDeflated[IO]().archive)
        .compile
        .to(Chunk)
      methods <- methodsOf(archived)
      _ = assertEquals(methods, List(ZipOutputStream.DEFLATED))
      obtained <- Stream
        .chunk(archived)
        .through(ZipUnarchiver[IO].unarchive)
        .flatMap { case (_, data) => data }
        .chunkAll
        .compile
        .lastOrError
      _ = assert(util.Arrays.equals(obtained.toArray, bytes))
    } yield ()
  }

  test("an entry read back out of an archive brings its CRC with it") {
    for {
      random <- Random.scalaUtilRandom[IO]
      expected <- random.nextBytes(4096)
      archived <- Stream
        .emit(storedEntry("test", expected) -> Stream.chunk[IO, Byte](Chunk.array(expected)))
        .through(archiver.archive)
        .compile
        .to(Chunk)
      // Archive the entries again with no CRC supplied anywhere. The one that comes back out of the unarchiver
      // already carries it.
      copied <- Stream
        .chunk(archived)
        .through(ZipUnarchiver[IO].unarchive)
        .map { case (entry, data) => entry.withUncompressedSize(Some(expected.length.toLong)) -> data }
        .through(archiver.archive)
        .compile
        .to(Chunk)
      methods <- methodsOf(copied)
      _ = assertEquals(methods, List(ZipOutputStream.STORED))
      obtained <- Stream
        .chunk(copied)
        .through(ZipUnarchiver[IO].unarchive)
        .flatMap { case (_, data) => data }
        .chunkAll
        .compile
        .lastOrError
      _ = assert(util.Arrays.equals(obtained.toArray, expected))
    } yield ()
  }

  test("directories and empty entries need no CRC from the caller") {
    val directory = ArchiveEntry[Some, Any]("dir/", Some(0L), isDirectory = true)
    val empty = ArchiveEntry[Some, Any]("empty.txt", Some(0L))
    for {
      archived <- Stream
        .emits(
          List(
            directory -> Stream.empty.covaryAll[IO, Byte],
            empty -> Stream.empty.covaryAll[IO, Byte]
          )
        )
        .through(archiver.archive)
        .compile
        .to(Chunk)
      names <- Stream
        .chunk(archived)
        .through(ZipUnarchiver[IO].unarchive)
        .map { case (entry, _) => entry.name }
        .compile
        .toList
      _ = assertEquals(names, List("dir/", "empty.txt"))
    } yield ()
  }
}
