# 11. Carry the native entry on `ArchiveEntry` behind a type class

Date: 2024-04-12

## Status

Accepted

## Context

`ArchiveEntry` models what every archive format has in common: a name, a size, a directory
flag, some timestamps. Real formats carry much more — tar has uid, gid, mode and extra
fields; zip has comments and extra records.

The first attempt at moving entries between formats converted through those four common
fields:

```scala
def to[B](implicit archiveEntry: ArchiveEntry[A], ctor: ArchiveEntryConstructor[B]): B =
  ctor.from(self)
```

Reading a tar entry and writing it straight back out therefore silently dropped everything
the model did not know about. For a copy operation — the most obvious thing to do with an
archive — that is data loss.

## Decision

Keep the native entry on the model and convert through type classes rather than through the
common fields:

```scala
case class ArchiveEntry[+Size[A] <: Option[A], +Underlying](
    name: String, ..., private val underlying: Underlying = ())
```

The field is `private` and shadowed by a method that requires evidence:

- `ArchiveEntryFromUnderlying[Size, U]` builds an `ArchiveEntry` when reading
- `ArchiveEntryToUnderlying[U]` produces the native entry when writing

A tar-to-tar or zip-to-zip copy then preserves everything the format supports, while a
cross-format copy or a synthesised entry degrades to the common core.

Getting this right needed a follow-up. The first version returned the *original* native entry
even after `withName` had changed the name, so a renamed entry archived under its old name.
The fix copies the native entry — for tar by round-tripping its 512-byte header — and
re-applies the changed fields. There is a regression test for exactly that.

## Consequences

- Copying between archives of the same format is lossless without `ArchiveEntry` having to
  model every field of every format.
- Users cannot accidentally couple to a format-specific type, since the native entry is only
  reachable with the right evidence in scope.
- There is a documented limitation, still carried as a comment at the top of `Tar.scala` and
  `Zip.scala`: the underlying information is lost if the name or directory flag changes in a
  way that makes the copy invalid.
- `ArchiveEntryFromUnderlying` always produces `ArchiveEntry[Option, U]` — reading never
  guarantees a size, which is why every unarchiver is `Unarchiver[F, Option, _]` (ADR 0009).

## References

- `dc4df41` "convert between archive entries" (2023-01-13) — the lossy first attempt
- `e675fe9` "refactoring" (2024-04-12) — case class, `Underlying`, the two type classes
- `98e9b70` "fix underlying information being incorrect" (2024-04-12) — defensive copies
- `core/src/main/scala/de/lhns/fs2/compress/ArchiveEntry.scala`
