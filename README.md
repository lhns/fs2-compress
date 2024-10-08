# fs2-compress

[![Typelevel Affiliate Project](https://img.shields.io/badge/typelevel-affiliate%20project-FFB4B5.svg)](https://typelevel.org/projects/)
[![build](https://github.com/lhns/fs2-compress/actions/workflows/build.yml/badge.svg)](https://github.com/lhns/fs2-compress/actions/workflows/build.yml)
[![Release Notes](https://img.shields.io/github/release/lhns/fs2-compress.svg?maxAge=3600)](https://github.com/lhns/fs2-compress/releases/latest)
[![Maven Central](https://img.shields.io/maven-central/v/de.lhns/fs2-compress_2.13)](https://search.maven.org/artifact/de.lhns/fs2-compress_2.13)
[![Apache License 2.0](https://img.shields.io/github/license/lhns/fs2-compress.svg?maxAge=3600)](https://www.apache.org/licenses/LICENSE-2.0)
[![Scala Steward badge](https://img.shields.io/badge/Scala_Steward-helping-blue.svg?style=flat&logo=data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAA4AAAAQCAMAAAARSr4IAAAAVFBMVEUAAACHjojlOy5NWlrKzcYRKjGFjIbp293YycuLa3pYY2LSqql4f3pCUFTgSjNodYRmcXUsPD/NTTbjRS+2jomhgnzNc223cGvZS0HaSD0XLjbaSjElhIr+AAAAAXRSTlMAQObYZgAAAHlJREFUCNdNyosOwyAIhWHAQS1Vt7a77/3fcxxdmv0xwmckutAR1nkm4ggbyEcg/wWmlGLDAA3oL50xi6fk5ffZ3E2E3QfZDCcCN2YtbEWZt+Drc6u6rlqv7Uk0LdKqqr5rk2UCRXOk0vmQKGfc94nOJyQjouF9H/wCc9gECEYfONoAAAAASUVORK5CYII=)](https://scala-steward.org)

Integrations for several compression algorithms with [Fs2](https://github.com/typelevel/fs2).

## Usage

### build.sbt

```sbt
libraryDependencies += "de.lhns" %% "fs2-compress-gzip" % "2.1.0"
libraryDependencies += "de.lhns" %% "fs2-compress-zip" % "2.1.0"
libraryDependencies += "de.lhns" %% "fs2-compress-zip4j" % "2.1.0"
libraryDependencies += "de.lhns" %% "fs2-compress-tar" % "2.1.0"
libraryDependencies += "de.lhns" %% "fs2-compress-bzip2" % "2.1.0"
libraryDependencies += "de.lhns" %% "fs2-compress-zstd" % "2.1.0"
libraryDependencies += "de.lhns" %% "fs2-compress-brotli" % "2.1.0"
libraryDependencies += "de.lhns" %% "fs2-compress-lz4" % "2.1.0"
```

### Example

## Gzip
```scala
import cats.effect.IO
import de.lhns.fs2.compress.{GzipCompressor, GzipDecompressor}
import fs2.io.compression._
import fs2.io.file.{Files, Path}

implicit val gzipCompressor: GzipCompressor[IO] = GzipCompressor.make()
implicit val gzipDecompressor: GzipDecompressor[IO] = GzipDecompressor.make()

for {
  _ <- Files[IO].readAll(Path("file"))
    .through(GzipCompressor[IO].compress)
    .through(Files[IO].writeAll(Path("file.gz")))
    .compile
    .drain
  _ <- Files[IO].readAll(Path("file.gz"))
    .through(GzipDecompressor[IO].decompress)
    .through(Files[IO].writeAll(Path("file")))
    .compile
    .drain
} yield ()
```

## Zip
```scala
/** Compress a directory into a .zip file
  * @param toCompress
  *   The directory to compress
  * @param output
  *   The where the .zip file should be written to
  * @tparam F
  *   The effect type to run in, e.g. cats.effect.IO
  * @return
  *   An effect in F which when run will compress the files in toCompress and write a .zip file to output
  * @note
  *   This example assumes that the toCompress is a directory and only contains files, no subdirectories
  */
def compressDirectory[F[_]: cats.effect.Async](toCompress: fs2.io.file.Path, output: fs2.io.file.Path): F[Unit] = {
  val zip = de.lhns.fs2.compress.ZipArchiver.make[F]()
  fs2.io.file
    .Files[F]
    .list(toCompress)
    .evalMap { path =>
      fs2.io.file
        .Files[F]
        .size(path)
        .map(size =>
          ArchiveEntry[Some, Unit](path.toString, uncompressedSize = Some(size)) -> fs2.io.file.Files[F].readAll(path)
        )
    }
    .through(zip.archive)
    .through(fs2.io.file.Files[F].writeAll(output))
    .compile
    .drain
}
```

## License

This project uses the Apache 2.0 License. See the file called LICENSE.
