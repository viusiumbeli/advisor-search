# Operating notes

How the service behaves once it is running: what it measures, what it does synchronously, where its
indexes stop being the right choice, and how it is built, published and secured. Sustained-load
numbers and the ceilings the system runs against are in [Load and limits](load-and-limits.md).

## Measured on this machine

All measured on this machine (Apple silicon, Docker Desktop), against the seeded corpus of 10
clients, 20 documents and 153 chunks.

| | |
| --- | --- |
| `docker compose up` from clean to healthy | 20 s |
| Application start | 2.9 s |
| Embedding warmup at startup | 26 ms |
| Seeding 20 documents through the real ingest path | 8.8 s |
| Ingesting the longest document (15,707 chars, 22 chunks) | 757 ms |
| `GET /search`, one probe, warm | 24 ms median |
| `GET /search`, 5 expansion probes, warm (batched inference) | 50 ms median |
| Test suite (66 tests, from clean, no build cache) | 27 s |
| API container resident memory | 760 to 840 MiB |
| Image size | 576 MB |

## Ingest is synchronous

A `201` means chunked, embedded and committed, so there is no window in
which a caller can read back a document that search cannot find. Embedding happens *before* the
transaction opens, so a connection is never held across model inference. At roughly 35 to 55 ms per
chunk this holds to around 100 chunks per request. The point to move ingest to a background job is
when p99 for `POST /clients/{id}/documents` passes about 5 s, which on these numbers means documents
beyond roughly 100,000 characters, or a sustained bulk import.

## There is deliberately no ANN index

An exact scan cannot miss a neighbour the way an approximate
one can, and at this size it is not the bottleneck. Measured on this corpus with vectors copied to
grow the table:

| Chunks | Exact scan for the top 50 |
| --- | --- |
| 153 | 1.4 ms |
| 10,000 | 30 ms |
| 100,750 | 177 ms |

So the exact scan is the right choice into the tens of thousands of chunks, and around 50,000 is
where an index starts to earn its keep:

```sql
CREATE INDEX ON document_chunks USING hnsw (embedding vector_cosine_ops);
```

That trades exactness for speed, which is a decision to make with a recall measurement in hand
rather than pre-emptively.

## The client query uses its index at scale

On a table of a dozen rows Postgres correctly ignores
the trigram index and scans sequentially, so the seeded corpus proves nothing about it. On a
synthetic 200,001-row copy of the same schema, with the same predicate, the plan is what the design
intends — one GIN index serving both arms:

```
 Limit (actual time=0.929..0.930 rows=1 loops=1)
   ->  Sort (actual time=0.928..0.929 rows=1 loops=1)
         Sort Key: (GREATEST(…, word_similarity('aldgatewealth', search_text))) DESC, last_name, …
         ->  Bitmap Heap Scan on clients_bulk (actual time=0.910..0.910 rows=1 loops=1)
               Recheck Cond: ((search_text ~~ '%aldgatewealth%') OR ('aldgatewealth' <% search_text))
               ->  BitmapOr (actual time=0.886..0.886 rows=0 loops=1)
                     ->  Bitmap Index Scan on clients_bulk_search_text_idx
                           Index Cond: (search_text ~~ '%aldgatewealth%')
                     ->  Bitmap Index Scan on clients_bulk_search_text_idx
                           Index Cond: (search_text %> 'aldgatewealth')
 Execution Time: 0.974 ms
```

## Model provenance

`models/checksums.sha256` is committed with the real hashes;
`./gradlew provisionModel` fetches `model.onnx` and `tokenizer.json` from a pinned Hugging Face
revision and fails the build on a mismatch, so a corrupted or swapped download cannot silently change
every embedding. Tests depend on that task, so a fresh clone runs `./gradlew test` without any manual
step. The model is not committed (90 MB) but *is* baked into the Docker image, so a running container
needs no network beyond Postgres.

Every chunk row records the model that produced it, and startup fails with a "reindex required"
message if the corpus contains vectors from a different model. Vectors from two models share a column
but not a space, and comparing them produces confident nonsense rather than an error.

## Extensions

`CREATE EXTENSION vector` needs a superuser the first time (`pg_trgm` is trusted).
The compose user is one; managed providers allow both from their extension whitelist.

## Ids and field bounds

Primary keys default to Postgres 18's native `uuidv7()`: the leading 48
bits are a unix-ms timestamp, so ids are time-ordered and primary-key inserts stay append-mostly
instead of scattering across the B-tree the way fully random v4 ids do — that locality is why the
project's Postgres floor is 18. Field bounds are enforced twice: the API validates first and
returns per-field 400s, and the schema carries matching `varchar` limits plus named `CHECK`
constraints (non-blank names, the 100,000-character content ceiling) as the backstop for any writer
that is not the API. The content cap is a `CHECK` rather than a `varchar` because it mirrors the
configurable `ingest.max-content-length`. `SchemaConstraintTest` proves all of it with raw SQL that
bypasses the API.

## Authentication

Setting `API_KEY` turns on an `X-API-Key` filter on every endpoint except the
health probes and the API documentation. Unset — the compose default — the filter does nothing, so
running the project locally needs no credentials. This is a shared secret for a demo API, not a user
identity system; real multi-tenancy would scope every query by the authenticated advisor's
organisation, which is a `WHERE` clause through `client_id` rather than a new subsystem.

## The image is layered

The runtime stage assembles Boot's extracted layers instead of copying
the fat jar, so the ~110 MB of dependencies live in their own image layer and a source change
re-pushes about 1 MB. CI publishes the multi-arch image on every push to main with SBOM and
provenance attestations, which is what `docker compose pull` fetches.

The image `docker compose pull` fetches is published from this repository:

```bash
docker buildx create --name multiarch --driver docker-container --use   # once; the default
                                                                       # driver cannot do multi-arch
echo "$GITHUB_TOKEN" | docker login ghcr.io -u viusiumbeli --password-stdin
docker buildx build --platform linux/amd64,linux/arm64 \
  -t ghcr.io/viusiumbeli/advisor-search:latest --push .
```

The build stage is pinned to `$BUILDPLATFORM`, so the Gradle build runs once on the host
architecture rather than once per target under emulation: both images together take about 100
seconds instead of the ten-plus minutes a naive multi-architecture build would spend emulating a
JDK. Only the small runtime stage is built per architecture.

The image exists so a reviewer never has to build. `docker compose up` on its own still builds
locally, so the repository never depends on the registry being reachable.

## Scaling ladder

This system is stage one of three, and each stage's trigger is named rather
than guessed. Stage one — the current corpus scale — is the exact scan and in-process embedding
measured above. Stage two, from roughly 50k chunks: the HNSW index whose DDL is
[above](#there-is-deliberately-no-ann-index), a floor
recalibration, async ingest once its p99 criterion trips, and vector quantization near the top end.
Stage three, tens of millions of rows and up, starts with the domain answer rather than
infrastructure: advisor search is tenant-scoped, so partitioning by organisation prunes a
100-million-row table to one advisor's book per query — after which each partition is back at a
scale this design handles. Only genuinely global search forces dedicated engines (BM25 in
OpenSearch, a vector store or pgvectorscale) and a batched GPU embedding service; at that point the
request profile inverts from CPU-bound to I/O-bound and virtual threads turn on with one property
(see [Decisions](../README.md#decisions-and-what-i-left-out)). You scale by changing the retrieval
architecture, not by swapping the web framework.

## Runtime and data-access choices

Four entries from [Decisions, and what I left out](../README.md#decisions-and-what-i-left-out) whose
reasoning is about how the process runs rather than about search, kept here in full.

- **Virtual threads.** The 2026 default for a blocking Spring MVC app — deliberately off here.
  JDK 24 removed pinning on `synchronized` (JEP 491), which killed the classic objection, but a JNI
  downcall still pins its carrier with no scheduler compensation, and this request *is* a JNI
  downcall: ~90% of search latency is ONNX inference. On the deploy's four shared vCPUs a handful
  of in-flight searches pin every carrier — the probe-starvation risk shrinks with core count but
  does not go away — and Tomcat's bounded pool is currently the service's only admission control.
  The flag is one line; what it would optimize — waiting — is not what this service does. The reversal trigger is stage three of the scaling
  ladder above: embedding moves out of process, the profile inverts to I/O-bound, the property
  goes on.
- **WebFlux / coroutines.** Same root sentence: reactive converts waiting into suspension, and
  there is almost no waiting here (~2–3 ms of JDBC in a 24–50 ms request; capacity is bounded by
  inference FLOPs regardless of dispatcher). Going reactive would force an R2DBC rewrite of the
  data layer — no `JdbcClient` equivalent, a hand-written codec for the `vector` type, and Flyway
  still needs the JDBC driver, so two drivers and two transaction models — while the blocking JNI
  inference gets wrapped back onto a thread pool anyway. Suspending controllers are WebFlux-only,
  so there is no coroutines-on-MVC middle path. Post-virtual-threads, reactive earns its keep in
  streaming and high-fan-out services; this is neither.
- **An ORM, Spring Data, or jOOQ.** `JdbcClient` is Spring's 2023 API for exactly this shape of
  service — the SQL is the product here (`DISTINCT ON` over a KNN subquery, `ts_headline`,
  `word_similarity`, a transaction-local `set_config`), and every abstraction degenerates to
  native-SQL strings for these queries while adding machinery for the two trivial inserts. jOOQ is
  the escalation path if this grew to dozens of typed queries; Spring Data JDBC is the addition if
  CRUD aggregates ever dominate. Both are additions, not rewrites.
- **A CDS/AOT training run in the image.** Needs a live database inside `docker build` (Flyway
  migrates during context refresh), breaks the `$BUILDPLATFORM` multi-arch design by forcing the
  training pass under QEMU, and would shave ~1 s off a 20 s number — a cost paid once per deploy
  or reboot on a VPS where the JVM otherwise just stays up.
