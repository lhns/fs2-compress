package de.lhns.fs2.compress

import cats.effect.Async
import fs2.Pipe
import fs2.io.{readInputStream, readOutputStream, toInputStream, writeOutputStream}
import org.apache.commons.compress.compressors.zstandard.{ZstdCompressorInputStream, ZstdCompressorOutputStream}

import java.io.{BufferedInputStream, OutputStream}

class ZstdCompressor[F[_] : Async](chunkSize: Int) extends Compressor[F] {
  override def compress: Pipe[F, Byte, Byte] = { stream =>
    readOutputStream[F](chunkSize) { outputStream =>
      stream
        .through(writeOutputStream(
          Async[F].blocking[OutputStream](new ZstdCompressorOutputStream(outputStream))
        ))
        .compile
        .drain
    }
  }
}

object ZstdCompressor {
  def apply[F[_] : Async](chunkSize: Int = Defaults.defaultChunkSize): ZstdCompressor[F] =
    new ZstdCompressor(chunkSize)
}

class ZstdDecompressor[F[_] : Async](chunkSize: Int) extends Decompressor[F] {
  override def decompress: Pipe[F, Byte, Byte] = { stream =>
    stream
      .through(toInputStream[F]).map(new BufferedInputStream(_, chunkSize))
      .flatMap { inputStream =>
        readInputStream(
          Async[F].blocking(new ZstdCompressorInputStream(inputStream)),
          chunkSize
        )
      }
  }
}

object ZstdDecompressor {
  def apply[F[_] : Async](chunkSize: Int = Defaults.defaultChunkSize): ZstdDecompressor[F] =
    new ZstdDecompressor(chunkSize)
}
