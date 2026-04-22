package de.lhns.fs2.compress

import cats.effect.IO
import fs2.Stream
import fs2.text
import munit.CatsEffectSuite
import org.apache.commons.compress.archivers.tar.TarArchiveEntry

import scala.concurrent.duration._

class TarUnarchiveSuite extends CatsEffectSuite {
  implicit val tarUnarchiver: TarUnarchiver[IO] = TarUnarchiver.make()

  private val expectedEntryNames = List(
    ("file1.txt", "file1 content"),
    ("file2.txt", "file2 content"),
    ("file3.txt", "file3 content"),
    ("file4.txt", "file4 content"),
    ("file5.txt", "file5 content")
  )

  test("tar unarchive") {
    for {
      entries <- ResourceUtil
        .resourceAsStream("/basic-text.tar")
        .through(TarUnarchiver[IO].unarchive)
        .flatMap(readEntryData)
        .compile
        .toList
    } yield assertEquals(entries, expectedEntryNames)
  }

  test("tar unarchive without pulling data") {
    for {
      entries <- ResourceUtil
        .resourceAsStream("/basic-text.tar")
        .through(TarUnarchiver[IO].unarchive)
        .map { case (entry, _) => entry.name }
        .compile
        .toList
    } yield assertEquals(entries, expectedEntryNames.map(_._1))
  }

  test("tar unarchive only pulling data of second entry") {
    for {
      entries <- ResourceUtil
        .resourceAsStream("/basic-text.tar")
        .through(TarUnarchiver[IO].unarchive)
        .tail
        .head
        .flatMap(readEntryData)
        .compile
        .toList
    } yield assertEquals(entries, List(("file2.txt", "file2 content")))
  }

  /** Read entry data and tuple with the filename
    */
  def readEntryData(tuple: (ArchiveEntry[Option, TarArchiveEntry], Stream[IO, Byte])): Stream[IO, (String, String)] =
    tuple._2.through(text.utf8.decode).map(s => tuple._1.name -> s.trim())
}
