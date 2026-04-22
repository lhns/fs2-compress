package de.lhns.fs2.compress

import cats.effect.IO
import cats.syntax.all._
import fs2.Pipe
import fs2.Pull
import fs2.Stream
import fs2.text
import munit.CatsEffectSuite

import java.util.zip.ZipEntry
import scala.concurrent.duration._

class ZipUnarchiveSuite extends CatsEffectSuite {
  implicit val zipUnarchiver: ZipUnarchiver[IO] = ZipUnarchiver.make()

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
        .through(ZipUnarchiver[IO].unarchive)
        .flatMap(readEntryData)
        .compile
        .toList
    } yield assertEquals(entries, expectedEntryNames)
  }

  test("zip unarchive without pulling data") {
    for {
      entries <- ResourceUtil
        .resourceAsStream("/basic-text.zip")
        .through(ZipUnarchiver[IO].unarchive)
        .map { case (entry, _) => entry.name }
        .compile
        .toList
    } yield assertEquals(entries, expectedEntryNames.map(_._1))
  }

  test("zip unarchive only pulling data of second entry") {
    for {
      entries <- ResourceUtil
        .resourceAsStream("/basic-text.zip")
        .through(ZipUnarchiver[IO].unarchive)
        .tail
        .head
        .flatMap(readEntryData)
        .compile
        .toList
    } yield assertEquals(entries, List(("file2.txt", "file2 content")))
  }

  /** Read entry data and tuple with the filename
    */
  def readEntryData(tuple: (ArchiveEntry[Option, ZipEntry], Stream[IO, Byte])): Stream[IO, (String, String)] =
    tuple._2.through(text.utf8.decode).map(s => tuple._1.name -> s.trim())

}
