# 12. Deprecate `ZipArchiver.make` for `makeDeflated` and `makeStored`

Date: 2024-10-11

## Status

Accepted

## Context

`ZipArchiver.make` took the compression method as a raw `Int` from `java.util.zip`:

```scala
def make[F[_]: Async, Size[A] <: Option[A]](
    method: Int = ZipOutputStream.DEFLATED,
    chunkSize: Int = Defaults.defaultChunkSize
): ZipArchiver[F, Size]
```

Once ADR 0009 parameterised the archiver over `Size`, this signature let the two parameters
be chosen independently — including `make[IO, Option](ZipOutputStream.STORED)`, a STORED
archiver that does not require an entry size. STORED needs the size in the local header, so
that combination cannot work, and nothing stopped anyone writing it.

## Decision

Deprecate `make` and expose the two combinations that are actually valid:

```scala
@deprecated("Use makeDeflated or makeStored instead", "2.2")
def make[F[_]: Async, Size[A] <: Option[A]](method: Int = ..., chunkSize: Int = ...)

def makeDeflated[F[_]: Async](chunkSize: Int = ...): ZipArchiver[F, Option]
def makeStored[F[_]: Async](chunkSize: Int = ...): ZipArchiver[F, Some]
```

The method and the size requirement now travel together and cannot be mismatched.

This is the freedom ADR 0005 was set up to allow: because constructors are private and all
construction goes through factories, the factory set could be reshaped without touching the
class.

## Consequences

- The invalid combination is no longer expressible.
- `make` is still present. Under `early-semver` (ADR 0017) removing it would be a breaking
  change, so it stays for the 2.x line — it has already outlived several minor releases.
- An untyped `Int` is gone from the public API, and the two names document what they do.

## References

- `718b227` "Mark `Zip.make` factory function as deprecated" (2024-10-11)
- `zip/src/main/scala/de/lhns/fs2/compress/Zip.scala`
