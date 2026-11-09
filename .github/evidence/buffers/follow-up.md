# Follow-up on the first buffers

The [first measurements](allocation-summary.tsv) showed excess allocations
when a large first buffer grew through multiple intermediate arrays. The
updated candidates share the same upstream JGit base and use the same
allocation and output fixtures.

| Candidate and workload | Allocation change from upstream |
| --- | ---: |
| Reftable with 1 ref | −96.38% |
| Reftable with 700 refs | −65.94% |
| Reftable with 60,000 refs | +0.05% |
| PACK with 32 bytes of input | −91.1% |
| PACK with 256 KiB + 257 bytes of input | +6.1% |
| PACK with 3 MiB + 257 bytes of input | +0.45% |

The Reftable writer reserves the known encoded size before serialization.
The PACK candidate grows from 64 KiB directly to the full logical block,
so it can allocate up to an additional 64 KiB per pack. A size hint for
the first object saved allocations for some medium single-object packs
but increased allocations by 49.3% for a pack that started with a
roughly 512 KiB blob followed by a 32 KiB blob. The bounded-growth PACK
candidate remains an experiment until the workload mix is characterized.

All 15 generated files matched upstream byte for byte. These synthetic
tests count allocated bytes. The PostgreSQL jobs exercise the separate
compaction candidate with the exact JGit commit from the review branch.
