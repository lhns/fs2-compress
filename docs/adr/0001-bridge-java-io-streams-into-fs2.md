# 1. Bridge java.io codec streams into fs2

Date: 2023-01-11

## Status

Accepted

## Context

Almost every compression codec on the JVM ships as a pair of `java.io` stream decorators:
`ZipOutputStream`, `BZip2CompressorOutputStream`, `ZstdOutputStream`, `LZ4FrameOutputStream`
and so on. They are blocking, they are stateful, and they expect to be handed a stream to
wrap. fs2 works the other way around: pull-based, effectful, non-blocking by default.

Reimplementing each codec natively in fs2 would mean porting compression formats, which is
neither realistic nor desirable for a library whose value is the fs2 integration itself.

## Decision

Wrap the existing `java.io` implementations rather than reimplement them, using fs2's own
bridges. Compression runs the codec's `OutputStream` inside `fs2.io.readOutputStream` and
feeds it with `writeOutputStream`; decompression turns the byte stream into an `InputStream`
with `fs2.io.toInputStream`, wraps it in the codec's `InputStream`, and reads it back with
`readInputStream`. Every blocking call goes through `Async[F].blocking`.

This shape is present in the first code commit (`9ed3d84`) and every codec added since has
followed it — see ADR 0014.

The same commit fixes a default chunk size for the whole library:

```scala
object Defaults {
  val defaultChunkSize: Int = 1024 * 64
}
```

64 KiB is deliberately far larger than fs2's own 4 KiB default. These paths cross into JNI
and syscalls per call, so the per-call overhead dominates and larger buffers pay off. It is
the default argument of essentially every `make` in the repo, and every instance can
override it.

## Consequences

- New codecs are cheap to add: the wrapping is mechanical and identical each time.
- The library inherits the semantics of the underlying streams, including the ones that
  hurt. Everything that ADR 0018 and ADR 0020 had to fix follows from these being blocking,
  uninterruptible, single-pass streams.
- Anything reachable through `java.io` is JVM-only, which is why ADR 0003 ends up with only
  two cross-built modules.
- `chunkSize` is not just a buffer size: `fs2.io.readOutputStream` allocates a
  `PipedStreamBuffer` of exactly that capacity, so it is also the amount of slack between
  producer and consumer. The cancellation tests exploit this by shrinking it to 64 bytes.

## References

- `9ed3d84` "added files" (2023-01-11) — the initial implementation and `Defaults.scala`
- `core/src/main/scala/de/lhns/fs2/compress/Defaults.scala`
