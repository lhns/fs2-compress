package de.lhns.fs2.compress

import cats.effect.IO
import fs2.{Chunk, Stream}
import munit.CatsEffectSuite

import java.util.Base64

/** zip4j is here so that encrypted archives can be handled at all, which the JDK zip implementation cannot do.
  *
  * Only reading is covered. Writing does not encrypt yet: Zip4JArchiver takes a password and hands it to the
  * ZipOutputStream, but never sets encryptFiles or an encryption method on the ZipParameters, so zip4j writes the
  * entries in the clear and the password has no effect. A round trip through this library would therefore pass whether
  * or not encryption worked, which is why there is no round trip test here.
  */
class Zip4JEncryptedSuite extends CatsEffectSuite {

  test("an encrypted archive written by the zip command reads back with the password") {
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
      assert(obtained(0) == ("file1.txt", "Hello world!"))
      assert(obtained(1) == ("subdir/file2.txt", "Hello from subdir!"))
    }
  }
}
