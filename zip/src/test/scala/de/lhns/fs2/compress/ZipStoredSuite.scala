package de.lhns.fs2.compress

import cats.effect.IO
import cats.effect.std.Random
import fs2.{Chunk, Stream}
import munit.CatsEffectSuite

import java.io.ByteArrayInputStream
import java.util
import java.util.zip.{CRC32, ZipEntry, ZipInputStream, ZipOutputStream}

/** Writing entries uncompressed.
  *
  * The zip header for a stored entry carries the CRC of the data and comes before it, so the caller has to work the CRC
  * out in advance and hand it over. These tests cover doing that, and what happens when it is missing.
  */
class ZipStoredSuite extends CatsEffectSuite {
  implicit val zipUnarchiver: ZipUnarchiver[IO] = ZipUnarchiver.make()

  private val archiver = ZipArchiver.makeStored[IO]()

  private def crcOf(bytes: Array[Byte]): Long = {
    val crc = new CRC32
    crc.update(bytes)
    crc.getValue
  }

  private def storedEntry(name: String, bytes: Array[Byte]): ArchiveEntry[Some, Any] = {
    val zipEntry = new ZipEntry(name)
    zipEntry.setSize(bytes.length.toLong)
    zipEntry.setCompressedSize(bytes.length.toLong)
    zipEntry.setCrc(crcOf(bytes))
    ArchiveEntry[Some, Any](name, Some(bytes.length.toLong)).withUnderlying(zipEntry)
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
      // Read the archive with a plain ZipInputStream, so that the method is read out of the file itself rather than
      // through this library's own conversion. Without this the test would still pass if the archiver quietly fell
      // back to DEFLATED.
      methods <- IO.blocking {
        val in = new ZipInputStream(new ByteArrayInputStream(archived.toArray))
        try Iterator.continually(in.getNextEntry).takeWhile(_ != null).map(_.getMethod).toList
        finally in.close()
      }
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

  test("a CRC on a differently named entry is reported rather than quietly dropped") {
    val bytes = "hello".getBytes
    val zipEntry = new ZipEntry("other")
    zipEntry.setSize(bytes.length.toLong)
    zipEntry.setCompressedSize(bytes.length.toLong)
    zipEntry.setCrc(crcOf(bytes))
    val entry = ArchiveEntry[Some, Any]("test", Some(bytes.length.toLong)).withUnderlying(zipEntry)

    Stream
      .emit(entry -> Stream.chunk[IO, Byte](Chunk.array(bytes)))
      .through(archiver.archive)
      .compile
      .drain
      .attempt
      .map {
        case Left(_: IllegalArgumentException) => ()
        case Left(other) => fail(s"expected an IllegalArgumentException but got $other")
        case Right(_) => fail("the mismatched name should have been reported")
      }
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
