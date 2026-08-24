# 3. Cross-build to Scala.js only where the codec allows it

Date: 2023-01-11

## Status

Accepted

## Context

fs2 itself runs on both the JVM and Scala.js, so a library built on it is expected to offer
the same. The codecs, however, are wrapped `java.io` implementations (ADR 0001), and most of
them exist only as JVM jars: zstd-jni is JNI, commons-compress and `org.brotli:dec` are
Java-only, and `java.util.zip` has no Scala.js equivalent.

## Decision

Cross-build each module only where its implementation permits, rather than holding the whole
library to the lowest common denominator.

In practice that leaves `core` and `gzip` cross-built, and everything else JVM-only. Gzip
survives because it does not wrap a `java.io` stream at all — it delegates to fs2's own
`Compression`, which is backed by `java.util.zip` on the JVM and Node's zlib on Scala.js. See
ADR 0006 for how that constraint settled.

`zip` was briefly cross-built and dropped in `94c0360`, once `java.util.zip` and
`java.nio.file.attribute.FileTime` made it impossible.

## Consequences

- Scala.js users get gzip and the core abstractions, and nothing else. That is an honest
  reflection of what is portable.
- Anything placed in `core` must link on both platforms. This is a real constraint, not a
  formality: it is why `OutputStreams` (ADR 0019) lives in `core/src/main/scalajvm` with a
  JVM-only `fs2-io` dependency, and why the shared test suites avoid `java.io` entirely.
- JS tests need `ModuleKind.CommonJSModule` so the linked bundle can require Node's zlib.
- The build matrix is three Scala versions across two platforms for two modules, and three
  Scala versions for the other nine.

## References

- `1099bb0` "modules" — introduces sbt-scalajs and the initial platform split
- `94c0360` "updated build.sbt" — drops JS from `zip`
- `ba2a8c9` "fixed gzip js compile" — keeps `gzip` portable via fs2's `Compression`
- `9523ca0` "js test modules" — CommonJS module kind for tests
