package de.lhns.fs2.compress

import cats.effect.IO
import cats.syntax.all._
import fs2.{Chunk, Stream}

/** How an `Unarchiver` behaves when the data of an entry is not read, or is read at the wrong time.
  *
  * An archive is read in one pass, so the data of an entry is only available while the archive is positioned at it.
  * That leaves two ways for a consumer to get it wrong, and they are answered differently. Moving on to the next entry
  * without reading the current one is allowed, and the data that was skipped is simply gone. Reading the data of an
  * entry that the archive has already moved past is a mistake, and fails.
  */
abstract class UnarchiveEntriesSuite[Underlying] extends CancellationSuite {

  /** Builds an archive out of the given entries, so that these tests have more than one entry to work with. */
  protected def archive(entries: List[(String, Chunk[Byte])]): IO[Chunk[Byte]]

  protected def unarchiver: Unarchiver[IO, Option, Underlying]

  protected def entrySize: Int = 8 * 1024

  private def sample: IO[(Chunk[Byte], List[(String, Chunk[Byte])])] =
    List("first", "second", "third")
      .traverse(name => randomBytes(entrySize).map(bytes => (name, bytes)))
      .flatMap(entries => archive(entries).map(archived => (archived, entries)))

  test("the entries can be listed without reading any of their data") {
    sample.flatMap { case (archived, entries) =>
      finishes(
        Stream
          .chunk(archived)
          .through(unarchiver.unarchive)
          .map { case (entry, _) => entry.name }
          .compile
          .toList
      ).map(names => assertEquals(names, entries.map { case (name, _) => name }))
    }
  }

  test("reading the data of every entry in turn gives back what was archived") {
    sample.flatMap { case (archived, entries) =>
      finishes(
        Stream
          .chunk(archived)
          .through(unarchiver.unarchive)
          .flatMap { case (entry, data) => data.chunkAll.map(chunk => (entry.name, chunk.toList)) }
          .compile
          .toList
      ).map(read => assertEquals(read, entries.map { case (name, chunk) => (name, chunk.toList) }))
    }
  }

  test("reading only part of an entry and then moving on finishes") {
    sample.flatMap { case (archived, entries) =>
      finishes(
        Stream
          .chunk(archived)
          .through(unarchiver.unarchive)
          .flatMap { case (entry, data) => data.take(1).drain ++ Stream.emit(entry.name) }
          .compile
          .toList
      ).map(names => assertEquals(names, entries.map { case (name, _) => name }))
    }
  }

  test("skipping an entry gives the following entry its own data") {
    sample.flatMap { case (archived, entries) =>
      finishes(
        Stream
          .chunk(archived)
          .through(unarchiver.unarchive)
          .drop(1)
          .head
          .flatMap { case (_, data) => data }
          .chunkAll
          .compile
          .lastOrError
      ).map { second =>
        assertEquals(second.toList, entries(1) match { case (_, chunk) => chunk.toList })
      }
    }
  }

  test("reading the data of an entry the archive has moved past fails") {
    sample.flatMap { case (archived, _) =>
      finishes(
        Stream
          .chunk(archived)
          .through(unarchiver.unarchive)
          .map { case (_, data) => data }
          .compile
          .toList
          .flatMap(_.head.compile.drain)
          .attempt
      ).map {
        case Left(_: IllegalStateException) => ()
        case Left(other) => fail(s"expected an IllegalStateException but got $other")
        case Right(_) =>
          fail("reading the data of an entry that the archive had already moved past was allowed to succeed")
      }
    }
  }
}
