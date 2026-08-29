# Load and limits

What the service does under sustained load, and the ceilings it runs against. Steady-state
measurements and the design notes behind them are in [Operating notes](operating-notes.md).

Everything on this page is produced by `./load-test.sh` — a committed, repeatable procedure —
against a disposable stack (`docker compose -p bench up -d`), so the numbers can be re-created
rather than trusted. The script measures search across three corpus scales and four concurrency
levels, ingest alone and under contention, and a mixed workload. It grows the corpus by copying
the seeded vectors — the same methodology as the
[exact-scan table](operating-notes.md#there-is-deliberately-no-ann-index) — and refuses to run
against anything that is not a pristine seed, because it writes.

## Platform

Absolute numbers move with hardware; the shapes — linear growth with chunk count,
inference-bound concurrency — do not.

| | |
| --- | --- |
| Host | MacBook Pro, Apple M3 Pro, 12 cores, 36 GB RAM, macOS 26.5.2 |
| Docker Desktop VM | 12 CPUs, 7.7 GiB (Docker 24.0.7) |
| Stack | the two compose containers as shipped; JVM warmed and `VACUUM ANALYZE` run before every measured cell |
| Client | per-request `curl` over localhost with no connection reuse, so connection setup is included and latencies are conservative |

## Search under load

200 requests per cell, unique plain-probe queries (no expansion):

| Corpus (docs / chunks / clients) | Concurrency | p50 | p95 | p99 | max | Throughput |
| --- | --- | --- | --- | --- | --- | --- |
| 20 / 153 / 10 | 1 | 33 ms | 50 ms | 56 ms | 137 ms | 22.7 req/s |
| 20 / 153 / 10 | 5 | 40 ms | 75 ms | 85 ms | 112 ms | 82.1 req/s |
| 20 / 153 / 10 | 10 | 55 ms | 92 ms | 121 ms | 187 ms | 101.9 req/s |
| 20 / 153 / 10 | 20 | 83 ms | 150 ms | 181 ms | 209 ms | 114.5 req/s |
| 1,008 / 7,900 / 10,001 | 1 | 61 ms | 106 ms | 114 ms | 120 ms | 12.1 req/s |
| 1,008 / 7,900 / 10,001 | 10 | 140 ms | 279 ms | 299 ms | 316 ms | 56.5 req/s |
| 13,008 / 99,700 / 10,001 | 1 | 761 ms | 846 ms | 926 ms | 1314 ms | 1.3 req/s |
| 13,008 / 99,700 / 10,001 | 10 | 1489 ms | 1630 ms | 1665 ms | 1666 ms | 6.5 req/s |

Two shapes matter more than any single cell. Latency grows with chunk count because both learned
arms are exact scans — these rows are the measured demonstration of the
[crossover](operating-notes.md#there-is-deliberately-no-ann-index) past which the exact scan is the
wrong tool. Within one corpus, added concurrency divides the same twelve cores: throughput rises (23
to 115 req/s at seed scale) while per-request latency stretches, which is inference-bound CPU rather
than lock contention — the same fact that keeps virtual threads off (see
[Runtime and data-access choices](operating-notes.md#runtime-and-data-access-choices)).

The large-corpus rows are the third arm's bill, and it is larger than the sparse scan alone. Before
the sparse column existed the same rows read 113 ms and 482 ms p50; the sparse arm's own scan at
99,700 chunks is about 420 ms, but the *dense* scan beside it went from 244 ms to about 520 ms,
because the two vectors' out-of-line chunks interleave in one TOAST relation and a scan of either
column now moves both through a 128 MB buffer cache. At the corpus that ships none of this shows —
seed-scale p50 moved from 15 to 33 ms at concurrency 1, most of it the third round trip and the
third arm's share of a JVM warmed by only twenty requests. Past the crossover the first change is
structural rather than an index: the sparse vectors in their own table, so each arm reads its own
bytes, and only then the question of indexing either column
([operating notes](operating-notes.md#there-is-deliberately-no-ann-index)).

## Ingest under load

| Scenario | Result |
| --- | --- |
| One 10 KB document (the brief's average), seed corpus | 927 ms, 10 chunks |
| Five 10 KB documents concurrently | per-request p50 2700 ms, max 2782 ms; 1.8 docs/s aggregate |
| One 99 KB document (just under the cap) | 6.5 s, 95 chunks |
| Search while the 99 KB ingest runs (200 requests, concurrency 10) | p50/p95/p99/max: 86 ms, 147 ms, 206 ms, 223 ms, 75.3 req/s |
| One 10 KB document again, at the large corpus | 904 ms — ingest cost is model inference, not corpus size |

Ingest cost is a function of document size, not corpus size — the same 10 KB document costs about
the same at 153 chunks as at 99,700. Every chunk now goes through two models: the dense pass in
batches of sixteen and the sparse pass in batches of four, the latter because its vocabulary-wide
output tensor is 27 MiB per chunk and is held twice while it is pooled (the rationale is on
`EMBED_BATCH` in `EmbeddingService` and `SPARSE_BATCH` in `SparseEncoder`). The sparse pass costs
about twice the dense one — its encoder is the same size, but the output head is as large again and
runs at every position — which is what took the 10 KB document from 269 ms to 0.93 s and the
maximum-size one from 2.3 s to 6.5 s. Five concurrent ingests triple per-request latency for the same
reason five concurrent searches stretch search: they queue for the same cores. A maximum-size
document is now 6.5 s of synchronous work, past the criterion for moving ingest behind a background
job (p99 over 5 s) in [Operating notes](operating-notes.md#ingest-is-synchronous), and until that
job exists it is what a caller's timeout has to be sized for. On this 12-core machine one background
ingest lifts search p95 by about half (92 to 147 ms); on the deploy's four shared vCPUs the same
contention bites harder — one ingest occupies a quarter of the cores rather than a twelfth — which
is the bounded-pool admission-control argument in the
[virtual-threads note](operating-notes.md#runtime-and-data-access-choices).

## Limits

The ceilings the system runs against, and what happens at each:

| Limit | Value | At the limit |
| --- | --- | --- |
| `content` length | 100,000 characters | Per-field `400` from the API; the schema `CHECK` backstops non-API writers. |
| tsvector lexeme pool (the generated `fts` column) | 1,048,575 bytes | `ERROR` at `INSERT`. Unreachable through the API: 100,000 pathological all-distinct-word characters measure 165,060 bytes (6× headroom), the seeded corpus's real prose — 107,464 characters — measures 52,054 bytes (20×), and the pathological shape fails only past ~850,000 characters. |
| tsvector positions | 16,383 per document | Silent: past ~16,000 words every further word reads back as position 16,383, and `ts_rank_cd` is cover density — positional by definition — so lexical ranking inputs quietly degrade. 100,000 characters ≈ 16,000 English words: the cap sits on this boundary (confirmed by measurement after the number was chosen, not the reason for it). |
| Encoder window | 256 wordpieces | Silent truncation — prevented per chunk by the chunker's hard-window fallback. Both models read the same tokenizer output; the sparse model would accept 512, the shared window is the dense model's. |
| Sparse vector storage | 16,000 non-zeros per value | `ERROR` at `INSERT`. Unreachable: `sparse.max-terms` prunes a chunk to 1,000, and the seeded corpus peaks at 666. 1,000 is also pgvector's HNSW ceiling, kept on purpose. |
| Exact vector scans | ~50,000 chunks | The crossover to the [HNSW indexes](operating-notes.md#there-is-deliberately-no-ann-index) for either column; the large-corpus search rows show why. |
| Synchronous ingest | 5 s p99 | Reached: at the cap a document now costs 6.5 s with two models per chunk (from 2.3 s with one), so the background job is the next change to ingest, not a threshold ahead of it. |
| Machine memory | 2 GB floor | A 1 GB limit is OOM-killed two documents into seeding; under 2 GB the api container settles at 1.37 GiB after seeding and 1.52 GiB after a 99 KB ingest. JVM plus two ONNX Runtime sessions and their native arenas — the sparse model's logits tensor alone is 27 MiB per chunk, held twice while it is pooled. Sized for in [Deploying it](../README.md#deploying-it). |

The 100,000-character cap itself is provenance plus headroom: the brief's clarification put the
average document at about 10 KB, so the cap is 10× the stated corpus while staying under both
tsvector ceilings. One number carrying two meanings is the awkward part — the `CHECK` is pinned to
the same 100,000 as `ingest.max-content-length`, and raising only the property would turn clean
400s into database constraint violations. So it cannot be raised alone: the property is bounded by
the same ceiling in `IngestProperties`, and a larger value fails startup rather than the first
oversized request. The named split, if documents ever
legitimately grow past this (a full prospectus or annual report does): the `CHECK` becomes a
physical rail at 500,000 — under the tsvector break point with margin — while the property stays
the product policy, always at or below the rail; past ~16,000 words the lexical arm changes too
(chunk-level FTS mirroring the semantic arm, or frequency-based `ts_rank`), and ingest goes async
per its criterion.
