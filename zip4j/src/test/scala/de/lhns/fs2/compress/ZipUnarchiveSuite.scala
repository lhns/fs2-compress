package de.lhns.fs2.compress

import cats.effect.IO
import fs2.Stream
import fs2.text
import munit.CatsEffectSuite
import net.lingala.zip4j.model.LocalFileHeader

import scala.concurrent.duration._

class Zip4JUnarchiveSuite extends CatsEffectSuite {
  implicit val zipUnarchiver: Zip4JUnarchiver[IO] = Zip4JUnarchiver.make()

  private val expectedEntryNames = List(
    ("file1.txt", "file1 content"),
    ("file2.txt", "file2 content"),
    ("file3.txt", "file3 content"),
    ("file4.txt", "file4 content"),
    ("file5.txt", "file5 content")
  )

  test("zip unarchive") {
    for {
      entries <- ResourceUtil
        .resourceAsStream("/basic-text.zip")
        .through(Zip4JUnarchiver[IO].unarchive)
        .flatMap(readEntryData)
        .compile
        .toList
    } yield assertEquals(entries, expectedEntryNames)
  }

  test("zip unarchive without pulling data") {
    for {
      entries <- ResourceUtil
        .resourceAsStream("/basic-text.zip")
        .through(Zip4JUnarchiver[IO].unarchive)
        .map { case (entry, _) => entry.name }
        .compile
        .toList
    } yield assertEquals(entries, expectedEntryNames.map(_._1))
  }

  test("zip unarchive only pulling data of second entry") {
    for {
      entries <- ResourceUtil
        .resourceAsStream("/basic-text.zip")
        .through(Zip4JUnarchiver[IO].unarchive)
        .tail
        .head
        .flatMap(readEntryData)
        .compile
        .toList
    } yield assertEquals(entries, List(("file2.txt", "file2 content")))
  }

  /** Read entry data and tuple with the filename
    */
  def readEntryData(tuple: (ArchiveEntry[Option, LocalFileHeader], Stream[IO, Byte])): Stream[IO, (String, String)] =
    tuple._2.through(text.utf8.decode).map(s => tuple._1.name -> s.trim())
}
