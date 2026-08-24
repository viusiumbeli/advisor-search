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
| 20 / 153 / 10 | 1 | 15 ms | 24 ms | 25 ms | 35 ms | 39.5 req/s |
| 20 / 153 / 10 | 5 | 20 ms | 46 ms | 65 ms | 78 ms | 112.7 req/s |
| 20 / 153 / 10 | 10 | 33 ms | 71 ms | 95 ms | 117 ms | 154.6 req/s |
| 20 / 153 / 10 | 20 | 63 ms | 130 ms | 150 ms | 173 ms | 188.3 req/s |
| 1,008 / 7,900 / 10,001 | 1 | 26 ms | 66 ms | 74 ms | 80 ms | 21.3 req/s |
| 1,008 / 7,900 / 10,001 | 10 | 71 ms | 178 ms | 225 ms | 243 ms | 92.7 req/s |
| 13,008 / 99,700 / 10,001 | 1 | 113 ms | 189 ms | 265 ms | 311 ms | 6.8 req/s |
| 13,008 / 99,700 / 10,001 | 10 | 482 ms | 944 ms | 1,069 ms | 1,186 ms | 17.7 req/s |

Two shapes matter more than any single cell. Latency grows linearly with chunk count because the
semantic arm is an exact scan — these rows are the measured demonstration of the [~50,000-chunk
HNSW crossover](operating-notes.md#there-is-deliberately-no-ann-index), and at 99,700 chunks with ten concurrent clients the p95 is already
near a second. Within one corpus, added concurrency divides the same twelve cores: throughput
rises (39 to 188 req/s at seed scale) while per-request latency stretches, which is
inference-bound CPU rather than lock contention — the same fact that keeps virtual threads off
(see [Runtime and data-access choices](operating-notes.md#runtime-and-data-access-choices)).

## Ingest under load

| Scenario | Result |
| --- | --- |
| One 10 KB document (the brief's average size), seed corpus | 269 ms, 10 chunks |
| Five 10 KB documents concurrently | per-request p50 710 ms, max 741 ms; 6.3 docs/s aggregate |
| One 99 KB document (just under the cap) | 1.7 s, 95 chunks |
| Search while that 99 KB ingest runs (200 requests, concurrency 10) | p50 42 ms, p95 93 ms — from p50 33 ms, p95 71 ms idle |
| One 10 KB document again, at the large corpus | 335 ms — ingest cost is model inference, not corpus size |

Ingest cost is a function of document size, not corpus size — the same 10 KB document costs about
the same at 153 chunks as at 99,700. Five concurrent ingests triple per-request latency for the
same reason five concurrent searches stretch search: they queue for the same cores. A
maximum-size document is 1.7 s of synchronous work, which is what a caller's timeout has to be
sized for; the criterion for moving ingest behind a background job (p99 over 5 s) is in
[Operating notes](operating-notes.md#ingest-is-synchronous). On this 12-core machine one background
ingest lifts search p95 by about a third (71 to 93 ms); on the deploy's four shared vCPUs the same
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
| Encoder window | 256 wordpieces | Silent truncation — prevented per chunk by the chunker's hard-window fallback. |
| Exact vector scan | ~50,000 chunks | The crossover to the [HNSW index](operating-notes.md#there-is-deliberately-no-ann-index); the large-corpus search rows show why. |
| Synchronous ingest | 5 s p99 | Move ingest behind a background job; at the cap a document costs 1.7 s. |
| Machine memory | 2 GB floor | JVM plus ONNX Runtime's native arenas; 256 MB OOMs, and the api container settles between 760 and 840 MB resident. Sized for in [Deploying it](../README.md#deploying-it). |

The 100,000-character cap itself is provenance plus headroom: the brief's clarification put the
average document at about 10 KB, so the cap is 10× the stated corpus while staying under both
tsvector ceilings. Its known weakness is that one number carries two meanings — the `CHECK` is
pinned to the same 100,000 as `ingest.max-content-length`, so raising only the property would
turn clean 400s into database constraint violations. The named split, if documents ever
legitimately grow past this (a full prospectus or annual report does): the `CHECK` becomes a
physical rail at 500,000 — under the tsvector break point with margin — while the property stays
the product policy, always at or below the rail; past ~16,000 words the lexical arm changes too
(chunk-level FTS mirroring the semantic arm, or frequency-based `ts_rank`), and ingest goes async
per its criterion.
