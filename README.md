# fs2-compress

[![Test Workflow](https://github.com/lhns/fs2-compress/workflows/test/badge.svg)](https://github.com/lhns/fs2-compress/actions?query=workflow%3Atest)
[![Release Notes](https://img.shields.io/github/release/lhns/fs2-compress.svg?maxAge=3600)](https://github.com/lhns/fs2-compress/releases/latest)
[![Maven Central](https://img.shields.io/maven-central/v/de.lhns/fs2-compress_2.13)](https://search.maven.org/artifact/de.lhns/fs2-compress_2.13)
[![Apache License 2.0](https://img.shields.io/github/license/lhns/fs2-compress.svg?maxAge=3600)](https://www.apache.org/licenses/LICENSE-2.0)
[![Scala Steward badge](https://img.shields.io/badge/Scala_Steward-helping-blue.svg?style=flat&logo=data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAA4AAAAQCAMAAAARSr4IAAAAVFBMVEUAAACHjojlOy5NWlrKzcYRKjGFjIbp293YycuLa3pYY2LSqql4f3pCUFTgSjNodYRmcXUsPD/NTTbjRS+2jomhgnzNc223cGvZS0HaSD0XLjbaSjElhIr+AAAAAXRSTlMAQObYZgAAAHlJREFUCNdNyosOwyAIhWHAQS1Vt7a77/3fcxxdmv0xwmckutAR1nkm4ggbyEcg/wWmlGLDAA3oL50xi6fk5ffZ3E2E3QfZDCcCN2YtbEWZt+Drc6u6rlqv7Uk0LdKqqr5rk2UCRXOk0vmQKGfc94nOJyQjouF9H/wCc9gECEYfONoAAAAASUVORK5CYII=)](https://scala-steward.org)

This project provides [fs2](https://github.com/typelevel/fs2) helper methods
for several compression algorithms.

## Usage

### build.sbt

```sbt
libraryDependencies += "de.lhns" %% "fs2-compress-gzip" % "0.3.0"
libraryDependencies += "de.lhns" %% "fs2-compress-zip" % "0.3.0"
libraryDependencies += "de.lhns" %% "fs2-compress-tar" % "0.3.0"
libraryDependencies += "de.lhns" %% "fs2-compress-bzip2" % "0.3.0"
libraryDependencies += "de.lhns" %% "fs2-compress-zstd" % "0.3.0"
libraryDependencies += "de.lhns" %% "fs2-compress-brotli" % "0.3.0"
```

## License

This project uses the Apache 2.0 License. See the file called LICENSE.
