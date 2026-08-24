# 6. Require `Compression[F]` for gzip rather than `LiftIO`

Date: 2023-09-08

## Status

Accepted

## Context

Gzip is the one codec implemented by delegating to fs2's `Compression` rather than wrapping a
`java.io` stream, which is what keeps it cross-built (ADR 0003).

fs2 3.7.0 reworked how `Compression` instances are obtained, and the first adaptation pinned
the JVM one directly:

```scala
private val compression: Compression[F] = Compression.forSync
```

That compiles on the JVM and breaks Scala.js, which has no `forSync`.

The next attempt, twelve minutes later, went back to `fs2.io.compression._` and satisfied its
new requirement by demanding `LiftIO` from the caller:

```scala
class GzipCompressor[F[_]: Async: LiftIO](...)
```

It worked, but it leaked an implementation detail into every user's constraint list: fs2's
Node-backed instance happens to be derived via `LiftIO`, and nothing about gzip compression
should require the caller to know that.

## Decision

Ask for the thing actually needed. `GzipCompressor` and `GzipDecompressor` require
`Compression[F]`:

```scala
class GzipCompressor[F[_]: Async: Compression] private (...)
```

The caller brings an instance with `import fs2.io.compression._` on either platform, and the
library stays platform-neutral without naming a platform-specific mechanism.

## Considered options

- **Pin `Compression.forSync`.** Simplest, and breaks Scala.js. Rejected.
- **Require `LiftIO`.** Works on both platforms, but constrains callers by an unrelated
  capability. Rejected after roughly four months in place.
- **Require `Compression[F]`.** Chosen.

## Consequences

- Gzip's constraint list says what it means, and gzip remains the only cross-built codec.
- `LiftIO` appears nowhere in the library today.
- Callers must import an instance. This is one extra import for gzip that the wrapped codecs
  do not need, since those only require `Async`.

## References

- `0e3d17a` "fs2 3.7.0" (2023-05-17) — pins `forSync`
- `cc1d3b2` "LiftIO" (2023-05-17) — the interim fix
- `e7fcb65` "type classes" (2023-09-08) — replaces `LiftIO` with `Compression`
- `gzip/src/main/scala/de/lhns/fs2/compress/Gzip.scala`
