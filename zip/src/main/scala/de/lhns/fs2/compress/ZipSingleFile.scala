package de.lhns.fs2.compress

import cats.effect.Async
import cats.syntax.functor._
import fs2.{Pipe, Stream}

import java.util.zip.ZipEntry

class ZipSingleFileCompressor[F[_] : Async](zipEntry: ZipEntry, chunkSize: Int) extends Compressor[F] {
  private val zip = ZipArchiver[F](chunkSize = chunkSize)

  override def compress: Pipe[F, Byte, Byte] = { stream =>
    Stream.emit((zipEntry, stream))
      .through(zip.archive)
  }
}

object ZipSingleFileCompressor {
  def apply[F[_] : Async](zipEntry: ZipEntry, chunkSize: Int = Defaults.defaultChunkSize): ZipSingleFileCompressor[F] =
    new ZipSingleFileCompressor(zipEntry, chunkSize)
}

class ZipSingleFileDecompressor[F[_] : Async](chunkSize: Int) extends Decompressor[F] {
  private val zip = ZipUnarchiver[F](chunkSize = chunkSize)

  override def decompress: Pipe[F, Byte, Byte] = { stream =>
    stream
      .through(zip.unarchive)
      .evalFilter {
        case (zipEntry, stream) if zipEntry.isDirectory =>
          stream.compile.drain.as(false)

        case _ =>
          Async[F].pure(true)
      }
      .head
      .flatMap(_._2)
  }
}

object ZipSingleFileDecompressor {
  def apply[F[_] : Async](chunkSize: Int = Defaults.defaultChunkSize): ZipSingleFileDecompressor[F] =
    new ZipSingleFileDecompressor(chunkSize)
}
