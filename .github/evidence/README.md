# DFS storage experiments

The [workflow](../workflows/dfs-storage-experiments.yml) pins the source and
tree of upstream JGit master and each candidate. Its matrix runs the JGit Core
test suite independently for each tree. The candidate branches change one
decision at a time.

## Allocation and format comparison

[Allocation results](buffers/allocation-summary.tsv) give the median of three
fresh JVMs per variant with Temurin 25.0.5+8. The comparison uses
[BufferEvidence.java](buffers/BufferEvidence.java) with the baseline, 4 KiB
Reftable start, or 64 KiB PACK start. Each invocation does eight warm-ups,
followed by 64 small or eight large operations. It measures current-thread
allocated bytes via `ThreadMXBean`. The allocation-only Reftable writes use a
null sink and the allocation-only PACK writes omit the flush. The separate
format check writes, reads, and hashes the files, which are byte-identical
across the three variants. The raw CSVs and SHA-256 list are in
[buffers](buffers/). These numbers measure allocation in the fixture.

## PostgreSQL ref publication

The workflow builds JGit 8.0.0-SNAPSHOT from the compaction candidate and
verifies that the [fixture](ReftableCommitCompactionNativeTest.java) loads that
JAR in jgit-storage-hibernate. Each job uses PostgreSQL 17.10 with fsync and
synchronous commit and requests 0 or 20 ms of added network round-trip time
through Toxiproxy. A same-connection SQL probe records the actual round-trip
time. Each measurement seeds a table with 8192 refs and publishes 32 additional
refs. The fixture verifies the first compaction decision, verifies the complete
ref set with a fresh handle and block cache, and repeats each policy six times
after warm-up.

- Default uses JGit's existing three-block limit.
- `reftable.maxCommitCompactionBytes = 65536` caps the input size of the top
  table eligible for inline compaction and leaves the usual three-block upper
  limit in place.
- `reftable.maxCommitCompactionBytes = 0` skips inline compaction and leaves
  maintenance compaction available.

The artifacts record the source and harness identifiers, first and p95 ref
latency, cumulative lock time, payload bytes, and table count. The cold check
times a complete ref enumeration and includes assertion overhead.
