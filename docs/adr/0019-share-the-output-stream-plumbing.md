# 19. Share the `readOutputStream` plumbing in `OutputStreams`

Date: 2026-08-24

## Status

Accepted

## Context

Every compressor and archiver had its own copy of the same plumbing: open
`fs2.io.readOutputStream`, build the codec stream on top of the pipe, feed it with
`writeOutputStream`, close it. Five plain compressors differed only in the constructor call.

That was tolerable while the plumbing was four lines. ADR 0018 made it subtle — the close has
to happen inside the stream, the release has to close the pipe before the stream, and errors
from the release have to be discarded — and none of that is guessable from reading a call
site. Eight copies of a subtlety is eight chances to get it wrong, and a new codec module
copied from an old one would silently not be cancellable.

## Decision

Put the plumbing in `OutputStreams` in `core`, with two entry points named after their fs2
counterparts:

- `readWrappedOutputStream(chunkSize)(wrapOutputStream)(write)` — like
  `fs2.io.readOutputStream`, except the stream handed to `write` is the caller's wrapper
  around the pipe, and closing it can be cancelled. Used by the three archivers, which supply
  their own entry loop.
- `throughOutputStream(chunkSize)(wrapOutputStream)` — the same as a `Pipe`, for codecs that
  just write everything they are given. Used by the five plain compressors, one line each.

`readOutputStream` does not exist on Scala.js, so this needs `fs2-io`, which `core` did not
depend on. Rather than give it up or push the helper elsewhere, `fs2-io` is added to `core`'s
**JVM row only** and the file lives in `core/src/main/scalajvm`, with the reason recorded in
`build.sbt`:

```scala
// fs2-io is JVM only here: OutputStreams wraps fs2.io.readOutputStream, which does not exist on JS.
```

The Scala.js artifact is unchanged.

## Consequences

- The tricky part is written down once, next to the code that does it, instead of eight
  times.
- `src/main` came out smaller than before ADR 0018 despite gaining the fix.
- **JVM consumers of the `core` artifact now get `fs2-io` transitively.** Every other module
  already depends on it, so in practice this affects only someone depending on `core` alone,
  but it is a change to a published artifact's dependencies and should not be undone lightly.
- `core` is now split across two source directories, which is a wrinkle for anyone adding to
  it: cross-platform code in `src/main/scala`, JVM-only in `src/main/scalajvm`.
- ADR 0014's module shape is superseded on this point — a new codec should call
  `OutputStreams.throughOutputStream` rather than copy the plumbing.

## References

- PRs #339 and #341, commit `a5a9bed`
- `core/src/main/scalajvm/de/lhns/fs2/compress/OutputStreams.scala`
- `build.sbt` — the `core` module's `jvmPlatform` settings
