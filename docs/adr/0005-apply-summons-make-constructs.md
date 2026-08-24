# 5. `apply` summons, `make` constructs, constructors are private

Date: 2023-09-08

## Status

Accepted

## Context

Originally `apply` was the constructor, so a caller wrote `GzipCompressor[IO]()` and passed
the result around explicitly.

But the four traits of ADR 0004 are capabilities: a function that compresses something wants
to say "give me any `Compressor[F]`", not "give me this specific gzip instance configured
this way". That is the standard implicit-summoner idiom in the Typelevel ecosystem, and it
needs `apply` free to mean "summon the instance in scope".

## Decision

In every companion object, `apply` summons and `make` constructs:

```scala
object ZstdCompressor {
  def apply[F[_]](implicit instance: ZstdCompressor[F]): ZstdCompressor[F] = instance

  def make[F[_]: Async](level: Option[Int] = None, ...): ZstdCompressor[F] =
    new ZstdCompressor(level, ...)
}
```

Call sites became `implicit val c = GzipCompressor.make()` followed by
`GzipCompressor[IO].compress`. The author treated this as the API-defining change and
released it as 1.0.0.

Seven months later the class constructors were made `private`, closing the remaining way to
bypass `make`.

## Consequences

- The constructor signature is an implementation detail. That freedom is exercised directly
  in ADR 0012, where `ZipArchiver.make` is deprecated and replaced by two named factories
  without touching the class.
- Every module follows the convention, which makes the codebase very uniform and gives new
  codec contributions an obvious shape to copy (ADR 0014).
- Instances must be brought into implicit scope by the caller, which is more ceremony for
  someone who just wants to compress one stream. The single-file adapters and the explicit
  `make(...)` form remain available for that.

## References

- `e7fcb65` "type classes" (2023-09-08) — introduces `make` and the summoner; released as
  1.0.0
- `1ba242e` "make constructors private" (2024-04-12) — the second stage
