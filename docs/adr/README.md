# Architecture Decision Records

Decisions that shaped this library and would be expensive or confusing to reverse without
knowing why they were made. Most were reconstructed from the commit history and closed pull
requests; the later ones were recorded as they were taken.

Each record is one file, numbered in the order the decision was made, and follows the same
shape: **Date**, **Status**, **Context**, **Decision**, **Consequences**. A **Considered
options** section appears only where the alternatives are actually on record — several of
these decisions went through an attempt that was committed and then abandoned, and those are
worth keeping. Where no alternatives are listed, none were found; the section is not padded
with invented ones.

Statuses used here: **Accepted**, **Proposed** (decided, not yet merged), and **Superseded
by NNNN**. A record is not edited once accepted — if a decision changes, a new record
supersedes it.

| # | Decision | Status |
|---|---|---|
| [0001](0001-bridge-java-io-streams-into-fs2.md) | Bridge `java.io` codec streams into fs2, and default to a 64 KiB chunk | Accepted |
| [0002](0002-one-module-per-codec.md) | Give each codec its own module | Accepted |
| [0003](0003-cross-build-to-scala-js-where-possible.md) | Cross-build to Scala.js only where the codec allows it | Accepted |
| [0004](0004-four-traits-and-single-file-adapters.md) | Model the four operations as traits, with single-file adapters | Accepted |
| [0005](0005-apply-summons-make-constructs.md) | `apply` summons, `make` constructs, constructors are private | Accepted |
| [0006](0006-require-compression-instance-for-gzip.md) | Require `Compression[F]` for gzip rather than `LiftIO` | Accepted |
| [0007](0007-second-zip-implementation-for-encryption.md) | Ship a second zip implementation for encryption | Accepted |
| [0008](0008-declare-entry-size-instead-of-buffering.md) | Stream archive entries by declaring the size up front | Accepted |
| [0009](0009-archiver-type-states-whether-size-is-required.md) | Let the archiver's type say whether a size is required | Accepted |
| [0010](0010-verify-the-declared-entry-size.md) | Verify the declared entry size while archiving | Accepted |
| [0011](0011-carry-the-native-entry-behind-a-type-class.md) | Carry the native entry on `ArchiveEntry` behind a type class | Accepted |
| [0012](0012-deprecate-zip-archiver-make.md) | Deprecate `ZipArchiver.make` for `makeDeflated` and `makeStored` | Accepted |
| [0013](0013-test-with-munit-cats-effect.md) | Test with munit-cats-effect | Accepted |
| [0014](0014-codec-modules-follow-a-fixed-shape.md) | Codec modules follow a fixed shape | Accepted |
| [0015](0015-second-brotli-implementation-for-compression.md) | Ship a second brotli implementation for compression | Accepted |
| [0016](0016-track-the-scala-3-lts-line.md) | Track the Scala 3 LTS line | Accepted |
| [0017](0017-publish-to-sonatype-central-under-early-semver.md) | Publish to Sonatype Central under early-semver | Accepted |
| [0018](0018-close-codec-streams-inside-the-stream.md) | Close codec streams inside the stream, not in a `Resource` | Accepted |
| [0019](0019-share-the-output-stream-plumbing.md) | Share the `readOutputStream` plumbing in `OutputStreams` | Accepted |
| [0020](0020-assert-cancellation-by-counting-not-timing.md) | Assert cancellation by counting bytes, not by elapsed time | Accepted |
| [0021](0021-skipping-entries-and-stale-reads.md) | Let entries be skipped, and fail when reading data the archive has passed | Proposed |
| [0022](0022-default-instances-in-a-default-object.md) | Ship default instances in a `default` object per companion | Proposed |

## Threads worth following

Several records only make sense together.

**How an entry knows its size.** 0008 introduced the `Size` parameter to stop buffering whole
entries in memory, 0009 refined which archivers demand it, 0010 added the check that the
declared size is true, and 0012 removed the factory that let the invalid combination be
expressed.

**Why blocking streams are hard here.** 0001 chose to wrap `java.io` implementations, which
is where every later difficulty comes from: 0018 for cancellation, 0019 for the shared
plumbing that fixed it in one place, 0020 for how it is tested, and 0021 for what happens
when a consumer skips an entry.

**How an instance reaches the caller.** 0004 made each operation a trait, 0005 made instances
something you summon implicitly rather than construct at the call site, and 0022 closes the
ceremony that left behind by letting you import a default one.

**When to add a module rather than change one.** 0002 established one artifact per codec,
0007 and 0015 both applied it by adding a second implementation of a format rather than
replacing the first, and 0014 wrote down the shape a new module follows.
