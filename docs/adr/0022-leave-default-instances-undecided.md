# 22. Leave default instances undecided for now

Date: 2026-08-26

## Status

Proposed.

## Context

Issue #154 asked for convenience on the companions, so that using a codec with no
configuration would not mean writing `make()` and binding the result. A follow-up question in
that issue suggested something better than the original proposal: put the convenience in an
object to import rather than in a method.

That was built and spiked. Every companion gained a nested `default` object holding an
implicit instance from `make()`:

```scala
import GzipCompressor.default._

GzipCompressor[IO].compress
```

The request is reasonable and the result is convenient. The question is not whether the idea
is any good; it is whether this library knows enough yet to commit to a shape.

## Decision

Do not add them yet. `make()` stays the only way to obtain an instance for now, and this is
recorded so the question can be picked up again rather than rediscovered.

**It is a one-way door.** Under early-semver (ADR 0017) a `default` object on each of about
twenty-two companions is public API for the whole 2.x line. `ZipArchiver.make` is the standing
example of how that goes: deprecated in ADR 0012 and still present, several minor releases
later, because removing it would be breaking. An implicit instance is harder to withdraw than
a method, because taking it out of scope does not fail a caller's build — it changes which
instance their code resolves to, quietly. Adding it later costs nothing; adding it now and
finding the shape wrong costs the rest of the major version.

**The shape is not settled.** Open questions, none of which have an obvious answer today:

- Implicit, or a plain value written out at the use site? A plain `GzipCompressor.default[IO]`
  has no resolution behaviour to reason about, but differs from `GzipCompressor.make()` by a
  few characters, which makes it hard to justify as a second name for one thing.
- What should happen where a caller has their own instance? Measured on this build, with
  `import GzipCompressor.default._` in scope alongside an instance configured with
  `deflateLevel = Some(9)`:

  | the instance you wrote | what is summoned | result |
  |---|---|---|
  | `implicit val c: Compressor[IO]` | `Compressor[IO]` | yours |
  | `implicit val c: Compressor[IO]` | `GzipCompressor[IO]` | the default |
  | `implicit val c: GzipCompressor[IO]` | `GzipCompressor[IO]` | yours, on 2.13 and 3 |
  | `implicit val c: GzipCompressor[IO]` | `GzipCompressor[IO]` | ambiguity error on 2.12 |

  The second row is the one to have an answer for. An instance annotated with the abstract
  trait is not a candidate for a summon of the concrete type at all, so the default is the only
  candidate. Without the import that line does not compile, so the effect of adding a default
  is to turn a compile error into a codec configured differently from how the caller wrote it.
  Whether that is acceptable, and whether a library should have to teach "annotate with the
  concrete type" to avoid it, is exactly what is undecided.
- The bottom two rows also mean an import and a caller's own instance cannot coexist on 2.12,
  which the build still cross-builds. That constraint may lift on its own.

These traits are also not type classes in the sense that makes implicit resolution
uncontroversial. There is no canonical gzip compressor for an `F` — deflate level, strategy
and chunk size are all legitimate variations, and zip carries a compression method as well
(ADR 0009). An instance is a bundle of configuration. That does not settle the question either
way, but it is why the rows above are not a detail.

## Considered options

- **Convenience methods on the companion**, as #154 originally proposed:
  `GzipCompressor.compress[IO]()`. Set aside: it saves a few characters, still needs the
  explicit `[IO]`, does not help where an instance is passed into a polymorphic function, and
  multiplies where a companion has more than one factory — `ZipArchiver` would need one per
  compression method.
- **Ship the `default` objects now.** Deferred rather than rejected, for the reasons above.
- **A non-implicit `default` value.** Still open; see the first question.

## Consequences

- Obtaining an instance is `make()`, and there is one way to do it (ADR 0005). A caller who
  wants no configuration writes one binding, which is the cost of waiting.
- The spike is not thrown away: it is on the `default-instances` branch, with a suite per
  module, so revisiting this does not start from nothing.
- Because the error a caller meets today is "no instance in scope", the four core traits carry
  an `@implicitNotFound` naming `make`.
- If this is revisited, the questions above are the ones to answer first, and the answer should
  arrive before the next major version rather than during one.

## References

- Issue #154 "Add convenience functions to companion objects"
- `default-instances` branch — the spike
- `core/src/main/scala/de/lhns/fs2/compress/{Compressor,Decompressor,Archiver,Unarchiver}.scala`
