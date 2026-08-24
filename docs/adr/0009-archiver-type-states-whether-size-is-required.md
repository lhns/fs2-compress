# 9. Let the archiver's type say whether a size is required

Date: 2024-10-10

## Status

Accepted. Refines ADR 0008.

## Context

ADR 0008 made the entry size explicit, and `ZipArchiver` was declared `Archiver[F, Some]` —
a size is always required — because the archiver could be configured to use the STORED
method, which needs one.

But zip's default DEFLATED method does not need the size up front. Requiring it anyway made
zip unusable for the case it was best suited to, as the contributor put it:

> Requiring Some for the Size of ZipArchive makes it impossible to work with if you have
> streams of entries where you don't know the size up front (without reading all that data
> into memory).

which is the very problem ADR 0008 set out to solve.

## Decision

Let each archiver's type state its own requirement, by parameterising `ZipArchiver` over
`Size` rather than fixing it:

```scala
class ZipArchiver[F[_]: Async, Size[A] <: Option[A]] private (method: Int, chunkSize: Int)
    extends Archiver[F, Size]
```

and exposing the two valid combinations as named factories, so the compression method and the
size requirement cannot be chosen independently:

- `makeDeflated` → `ZipArchiver[F, Option]`
- `makeStored`   → `ZipArchiver[F, Some]`

Today `TarArchiver` and `Zip4JArchiver` are `Archiver[F, Some]`, `ZipArchiver` is whichever
the factory says, and every unarchiver is `Unarchiver[F, Option, _]` — reading back, a size
may legitimately be absent.

## Considered options

- **Keep `Archiver[F, Some]` everywhere.** Rejected: forces callers back to buffering.
- **Make `ZipArchiver` an `Archiver[F, Option]` and let `Size` be contravariant**, so an
  `Archiver[F, Option]` is usable where `Archiver[F, Some]` is expected. This was actually
  committed, then reverted a day later: it makes the relationship implicit and still leaves
  one archiver claiming a single fixed answer.
- **Parameterise the archiver and name the factories.** Chosen: the requirement travels with
  the instance, and the invalid combinations cannot be constructed.

## Consequences

- `makeDeflated`/`makeStored` express what a bare `method: Int` could not, which is why the
  old factory is deprecated in ADR 0012.
- Signatures involving archivers now carry a higher-kinded parameter, which is a real
  readability cost for a library whose main types are otherwise simple pipes.
- Choosing between the two zip modules is a type-level decision (ADR 0007).

## References

- `8d58cf7` "Make ZipArchiver work with Option instead of Some" (2024-10-09), PR #148 — the
  contravariance attempt
- `944cc1c` "Add Size parameter to ZipArchive" (2024-10-10) — reverts the variance,
  parameterises the class, adds the named factories
- `zip/src/main/scala/de/lhns/fs2/compress/Zip.scala`
