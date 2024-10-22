package de.lhns.fs2.compress

import cats.effect.IO
import fs2.{Chunk, Stream}
import munit.CatsEffectSuite

import java.nio.charset.StandardCharsets.UTF_8

class ArchiverSuite extends CatsEffectSuite {

  test("checkUncompressedSize does nothing when there is no size") {
    for {
      _ <- Stream(
        archiveEntryNoSize("file1.txt", "Hello world!"),
        archiveEntryNoSize("subdir/file2.txt", "Hello from subdir!")
      )
        .through(Archiver.checkUncompressedSize)
        .evalMap { case (_, stream) =>
          stream.compile.drain
        }
        .compile
        .drain
    } yield ()
  }

  test("checkUncompressedSize does nothing for correct sizes") {
    for {
      _ <- Stream(
        archiveEntryWithSize("file1.txt", 12, "Hello world!"),
        archiveEntryWithSize("subdir/file2.txt", 18, "Hello from subdir!")
      )
        .through(Archiver.checkUncompressedSize)
        .evalMap { case (_, stream) =>
          stream.compile.drain
        }
        .compile
        .drain
    } yield ()
  }

  test("checkUncompressedSize finds incorrect size") {
    interceptMessageIO[IllegalStateException](
      "Entry size of 18 bytes does not match size of 20000 bytes specified in header"
    ) {
      Stream(
        archiveEntryWithSize("file1.txt", 12, "Hello world!"), // correct size
        archiveEntryWithSize("subdir/file2.txt", 20000, "Hello from subdir!") // incorrect size
      )
        .through(Archiver.checkUncompressedSize)
        .evalMap { case (_, stream) =>
          stream.compile.drain
        }
        .chunkAll
        .compile
        .drain
    }
  }

  private def archiveEntryWithSize(
      name: String,
      size: Long,
      content: String
  ): (ArchiveEntry[Some, Any], Stream[IO, Byte]) = {
    val contentBytes = content.getBytes(UTF_8)
    (ArchiveEntry(name, Some(size)), Stream.chunk(Chunk.array(contentBytes)))
  }

  private def archiveEntryNoSize(
      name: String,
      content: String
  ): (ArchiveEntry[Option, Any], Stream[IO, Byte]) = {
    val contentBytes = content.getBytes(UTF_8)
    (ArchiveEntry(name), Stream.chunk(Chunk.array(contentBytes)))
  }

}
