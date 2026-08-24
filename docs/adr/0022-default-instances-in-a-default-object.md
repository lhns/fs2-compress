# 22. Ship default instances in a `default` object per companion

Date: 2026-08-25

## Status

Proposed

## Context

ADR 0005 made instances something the caller summons implicitly, and recorded the cost in its
own Consequences: "Instances must be brought into implicit scope by the caller, which is more
ceremony for someone who just wants to compress one stream."

A contributor proposed closing that gap with convenience methods on every companion —
`GzipCompressor.compress[F]()` instead of `GzipCompressor.make(...).compress`. The maintainer
was unconvinced: it runs opposite to the type-class direction fs2 took, every companion needs
one, and it mostly shortens `make`. Measured, it saves about seven characters and still needs
the explicit `[IO]`. He counter-proposed importable default instances instead. The discussion
stopped there, unresolved, for two years.

The counter-proposal is the better fit, because it helps in the case the library actually
documents: the README's examples pass an instance *implicitly into a polymorphic function*,
which an import satisfies and a `compress[F]()` helper does not.

## Decision

Each companion gains a nested `object default` holding an implicit instance built from `make()`
with every argument left alone:

```scala
object GzipCompressor {
  def apply[F[_]](implicit instance: GzipCompressor[F]): GzipCompressor[F] = instance
  def make[F[_]: Async: Compression](...): GzipCompressor[F] = new GzipCompressor(...)

  object default {
    implicit def defaultGzipCompressor[F[_]: Async: Compression]: GzipCompressor[F] = make()
  }
}
```

`apply` still summons and `make` still constructs; this sits on top of both and supersedes
nothing in ADR 0005.

Four choices, each with a reason:

- **The concrete type, not the trait.** `GzipCompressor[F]`, never `Compressor[F]`. The
  summoner needs the concrete type, and an abstract `Compressor[F]` request is still satisfied
  by subtyping. Annotating the trait would serve one and break the other.
- **A nested object, not the companion body.** Implicits placed directly in a companion land in
  the type's implicit scope, so a summon would never fail and a mis-wired instance would be
  invisible. The nesting is what makes it opt-in. Moving these into the companion body later
  would quietly remove the ability to opt out.
- **`implicit def`, not `given`.** `given` members need `import X.default.given`, which does not
  cross-compile with 2.12 and 2.13.
- **Members named `default<ClassName>`.** Not the obvious `gzipCompressor`: that is exactly what
  a caller names their own instance, and a name collision is the one case where the import wins
  over a local definition. See the measurements below.

Two codecs do not get a `default`, because a default there would be a silent semantic choice:

- **Snappy** gets `framed`, `basic` and `hadoopCompatible` instead. `mode` is the only required
  parameter in the library and is required on purpose, since the modes produce incompatible
  bytes. A default would turn a compile error into a stream that fails only when something
  tries to read it back.
- **`ZipArchiver.default` is DEFLATED only.** STORED needs the uncompressed size of every entry
  up front, so it is not something to arrive at by importing a thing called `default`;
  `makeStored` stays an explicit call. It is also broken independently, never setting CRC or
  compressed size.

## Considered options

- **Convenience methods on the companion**, as originally proposed. Rejected: it saves very
  little, adds a parallel API beside the summoner rather than composing with it, and multiplies
  where a companion has several factories (`archiveDeflated`, `archiveStored`).
- **Implicits directly in the companion body.** Rejected: always-on, not opt-out.
- **`given` definitions.** Rejected: does not cross-compile.
- **Nothing.** Defensible — the ceremony is one line — but the question deserved an answer
  rather than a third year open.

## Consequences

- One import replaces one `implicit val`, and the summoner keeps working unchanged.
- **The caching rationale does not hold.** Default objects were attractive partly because they
  "would make it possible to cache the instances". They do not: every class captures its
  `Async[F]` evidence as a constructor field, so an instance is tied to one `F` and one evidence
  value, and no `val` can abstract over `F[_]`. These are `implicit def`s that allocate per
  summon. That cost is negligible — a stateless object of a few fields, at wiring time, beside
  a pipe that allocates 64 KiB chunks — but the change rests on ergonomics alone.
- **Mixing an import with your own instance behaves differently per compiler.** Measured, not
  assumed: with distinct names, Scala 2.13 and 3 prefer the local definition, while **2.12
  reports an ambiguity error**. A caller who configures a codec should drop the import. The
  README says so.
- **A name collision reverses that.** If the imported member and the local instance share a
  simple name, Scala 3 silently picks the imported default and the local settings are lost.
  This is why the members are `defaultGzipCompressor` rather than `gzipCompressor` — the latter
  is precisely what callers write, including this repo's own test suites.
- `default` does not remove constraints, only ceremony. Gzip still needs a `Compression[F]`, and
  on Scala.js that means `import fs2.io.compression._`, since fs2 provides no instance there
  otherwise. The flagship codec of the README is the one where the import alone is not enough.
- Nineteen companions, 17 `default` objects and 5 mode objects, each a copy of the same four
  lines with no shared abstraction possible, since every `make` has a different signature. That
  is the boilerplate the maintainer objected to in 2024; it is bounded and mechanical.
- The contributor checklist in ADR 0014 gains a step: a new codec ships a `default` object, or
  mode-named ones where a default would be a guess. ADR 0014 is left as it stands.
- `@implicitNotFound` on the four core traits now names both ways to get an instance, since the
  default message mentions neither.

## References

- PR #154, by Jakob Merrild, and the discussion on it
- ADR 0005 for the summoner convention this builds on, ADR 0014 for the checklist it extends
- `core/src/main/scala/de/lhns/fs2/compress/{Compressor,Decompressor,Archiver,Unarchiver}.scala`
