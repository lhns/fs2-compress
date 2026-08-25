package de.lhns.fs2.compress

import cats.effect.IO
import fs2.{Chunk, Stream}
import munit.CatsEffectSuite

import scala.concurrent.duration._
import scala.concurrent.duration.Duration

/** Checks that a codec's memory use does not grow with the amount of data put through it.
  *
  * The check is the heap itself rather than a measurement. These run in a JVM whose heap is far smaller than the volume
  * being streamed, so anything that accumulates in proportion to the input runs out of memory and the test dies.
  * Measuring instead, by calling `System.gc()` and reading `Runtime`, would prove much less: the collection is a hint
  * the JVM may ignore, and what is in use conflates live data with garbage that has not been collected yet.
  *
  * Nothing here holds on to the stream contents. The bytes are counted as they go past, so anything retained is
  * retained by the codec.
  *
  * An ordinary test run skips these, since they are slow and prove nothing without a small heap. To run them, set
  * FS2_COMPRESS_MEMORY_CHECK and a heap far below the volume above:
  *
  * {{{
  * FS2_COMPRESS_MEMORY_CHECK=1 FS2_COMPRESS_TEST_XMX=-Xmx96m sbt "testOnly *MemorySuite"
  * }}}
  */
abstract class MemorySuite extends CatsEffectSuite {

  protected def compressor: Compressor[IO]

  protected def decompressor: Decompressor[IO]

  /** How much to put through the codec. It only has to be large relative to the heap the check runs with; bzip2 lowers
    * it because it is an order of magnitude slower than the others.
    */
  protected def megabytes: Int = 256

  /** Generous, but not unbounded. A codec that holds on to everything does not always die quickly: with a heap this
    * small it can spend a long time collecting garbage instead, and without a deadline the check would hang rather than
    * fail.
    */
  override def munitIOTimeout: Duration = 10.minutes

  /** How many bytes the check puts through. Archivers that need an entry's size up front are given this. */
  protected final def bytes: Long = megabytes.toLong * 1024 * 1024

  private val block: Chunk[Byte] = {
    val data = new Array[Byte](64 * 1024)
    // Incompressible, so that the codec cannot make the work disappear.
    new scala.util.Random(1L).nextBytes(data)
    Chunk.array(data)
  }

  test("a large stream is compressed and decompressed in constant memory") {
    Stream
      .emits(Seq.fill(megabytes * 16)(block))
      .flatMap(Stream.chunk[IO, Byte])
      .through(compressor.compress)
      .through(decompressor.decompress)
      .chunks
      .fold(0L)((total, chunk) => total + chunk.size)
      .compile
      .lastOrError
      .map(total => assertEquals(total, bytes))
  }
}
