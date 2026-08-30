# Operating notes

How the service behaves once it is running, how it is built and deployed, and the reasoning behind
its runtime shape. Sustained-load numbers and the ceilings the system runs against are in
[Load and limits](load-and-limits.md).

## Measured environment

Every number here and in [Load and limits](load-and-limits.md) was measured on a MacBook Pro
(Apple M3 Pro, 12 cores, 36 GB RAM, macOS 26.5.2), with Docker Desktop giving the VM 12 CPUs and
7.7 GiB, against the seeded corpus of 10 clients, 20 documents and 153 chunks.

| | |
| --- | --- |
| `docker compose up` from clean to healthy | 21–27 s (two runs) |
| Application start | 4.0 s |
| Model warm-ups at startup | 20–60 ms dense, 50–70 ms sparse; the expansion lexicon (77 phrasings and 65 expansions) embedded in 0.3–0.6 s across two starts |
| Seeding 20 documents through the real ingest path | 13.4 s |
| Ingesting a 10 KB document (13 chunks) | 1.14 s: 348 ms dense, 729 ms sparse |
| `GET /search`, one probe, warm | 17 ms median (server side 12–16 ms: the query's forward pass and lexicon match 2–4 ms, keyword 1–2 ms, sparse 1–2 ms, one semantic scan 7–9 ms) |
| `GET /search`, expanded to five probes, warm | 45 ms median (server side 41–43 ms; the expansions' vectors are precomputed, so the difference is four more semantic scans at ~34 ms together) |
| Test suite (139 tests, from clean, no build cache) | 41 s |
| API container resident memory | 1.37 GiB after seeding and 1.52 GiB after a 99 KB ingest under a 2 GB limit; 1.6–1.8 GiB with no limit, where the heap is left to grow |
| Image size | 716 MB |

## Ingest is synchronous

A `201` means chunked, encoded by both models and committed, so there is no window in which a caller
can read back a document that search cannot find. Both encodings happen *before* the transaction
opens, so a connection is never held across model inference. A chunk now costs roughly 25 ms of
dense inference plus 50 ms of sparse — the sparse model's encoder is the dense model's size, but its
vocabulary-wide output head is as large again and runs at every position — so the criterion this
section has always named, a p99 for `POST /clients/{id}/documents` above about 5 s, is reached at
roughly 65 chunks: 6.5 s for a document at the 100,000-character cap (95 chunks), against 0.93 s
for the brief's average 10 KB. The criterion has therefore been met rather than moved: the next
change to ingest is the background job, and until it exists a caller's timeout has to be sized for
the cap.

## There is deliberately no ANN index

An exact scan cannot miss a neighbour the way an approximate one can, and at this size it is not the
bottleneck. Measured on this corpus with vectors copied to grow the table:

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

The sparse column is scanned exactly for the same reason, and the numbers are of the same shape:
1.2–2.6 ms at 153 chunks against the dense arm's 1.5–1.8 ms, and about 420 ms at 99,700 chunks
against about 520 ms for the dense arm — which was 244 ms before the sparse column existed. That
last number is the real cost of the third arm at scale, and it lands on the *dense* scan: both
vectors are stored out of line, their chunks interleave in the one TOAST relation, and a scan that
wants only the dense vector still moves the sparse bytes through the buffer cache beside it. The
table is 503 MB at that size, against a 128 MB `shared_buffers`, so every exact scan is now a read of
the whole relation rather than half of it. At the corpus that ships none of this is visible; past
the crossover, the first change is to put the sparse vectors in a table of their own so each arm
reads only its own bytes — a one-migration change — and only then to ask whether either column wants
an index. Two facts to hold on to if one is ever wanted: pgvector's only sparse index is HNSW (`sparsevec_ip_ops` for
`<#>`; IVFFlat does not support `sparsevec`), and it accepts at most 1,000 non-zeros per value, which
`sparse.max-terms` keeps every stored vector under. And an HNSW index serves `ORDER BY … LIMIT k`
over chunks, not the per-document `min()` the search runs — indexing either column means going back
to the chunk-bounded shortlist that [search design](search-design.md#documents-three-retrievers)
rejects, so that decision, too, wants a recall measurement first.

## The client query uses its index at scale

On a table of a dozen rows Postgres correctly ignores the trigram index and scans sequentially, so
the seeded corpus proves nothing about it. On a synthetic 200,001-row copy of the same schema, with
the same predicate, the plan is what the design intends — one GIN index serving both arms:

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

`models/checksums.sha256` is committed with the real hashes; `./gradlew provisionModel` fetches five
files — the dense model and its tokenizer from `sentence-transformers/all-MiniLM-L6-v2`, the sparse
model's ONNX export from `seerware/opensearch-neural-sparse-encoding-doc-v2-mini`, and its tokenizer
and IDF table from `opensearch-project/opensearch-neural-sparse-encoding-doc-v2-mini` — each from a
pinned Hugging Face revision, and fails the build on a mismatch, so a corrupted or swapped download
cannot silently change every vector. Tests depend on that task, so a fresh clone runs
`./gradlew test` without any manual step. The models are not committed (230 MB) but *are* baked into
the Docker image, so a running container needs no network beyond Postgres. The sparse export is a
third-party mirror of the official weights — its safetensors, tokenizer and IDF blobs are
byte-identical to the official repository's, and `SparseEncoderTest` pins its output against the
official PyTorch checkpoint — but the checksum protects integrity, not availability: mirroring the
three files to a repository this project controls is the named follow-up before anything depends on
that mirror still existing.

Every chunk row records both models that produced it, and startup fails with a "reindex required"
message if the corpus contains vectors from a different one. Vectors from two dense models share a
column but not a space, and comparing them produces confident nonsense rather than an error; term
weights from two sparse checkpoints share a vocabulary axis but not a scale, which is the same
failure without even the appearance of being wrong. The sparse columns arrived in `V2` as `NOT NULL`
— legal because no instance held data when it shipped — so an older volume with rows fails the
migration with "column contains null values" and has to be recreated rather than half-served.

## Readiness gates traffic, not only the probe

Tomcat accepts connections as soon as the context refreshes, which is before the model checks, the
warm-ups and the seed runner have run — so the published port is open while the corpus is still being
embedded. A filter answers `503` on everything except the probes, the API documentation and the
console page until Spring Boot flips readiness, which it does only once every runner has returned.
Without it the window is not merely cosmetic: a `POST` landing inside it can commit vectors from one
model into a corpus built by another, moments before the check that exists to catch that fails the
instance, and under `restart: unless-stopped` the window reopens on every crash-loop iteration. The
Caddy sidecar waits on the same signal, through `depends_on: condition: service_healthy`.

## Seeding on demand

The deployed instance starts empty, which leaves it unable to demonstrate the brief's own examples —
they are about documents it does not have. `POST /demo-corpus` loads the same corpus the `seed`
profile loads at startup, through the same service calls, so nothing about the data differs; only
when it arrives does. About four seconds warm on the machine above, where the same work measured
8.8 s cold at startup.

It is registered unconditionally rather than behind a profile, because a profile-gated endpoint is
absent exactly where it is wanted: `deploy/docker-compose.prod.yml` sets no profile at all, and
adding one there to enable an endpoint would put the auto-seeding runner one typo away. What makes
that safe is not a flag but a precondition — the load is refused unless the corpus is empty, so demo
data can never mix with data somebody posted. That matters more than duplicate rows: the semantic
floor is relative, so twenty unrelated documents can lift the cut-off past the document a search was
looking for, and a search answering nothing reads as a broken search. The check has to run before
`SeedService.seed()` rather than be inferred from its summary, because a second run reports
`(0, 0, 20)` — which is also what loading nothing looks like. The guard lives on the on-demand path
only; the startup runner still merges into whatever a persistent volume already holds.

Two consequences worth knowing. Unlike the runner, this embeds *after* readiness has flipped, so for
those seconds search competes with inference and a concurrent query sees a partly-loaded corpus —
correct but thin. And there is no way back: with no delete endpoints by design, a 409 is cleared only
by recreating the volume, so an instance that has been used cannot be shown the demo corpus.

## Postgres extensions

`CREATE EXTENSION vector` needs a superuser the first time (`pg_trgm` is trusted). The compose user
is one; managed providers allow both from their extension whitelist.

## Ids and field bounds

Primary keys default to Postgres 18's native `uuidv7()`: the leading 48 bits are a unix-ms
timestamp, so ids are time-ordered and primary-key inserts stay append-mostly instead of scattering
across the B-tree the way fully random v4 ids do — that locality is why the project's Postgres floor
is 18. Field bounds are enforced twice: the API validates first and returns per-field 400s, and the
schema carries matching `varchar` limits plus named `CHECK` constraints (non-blank names, the
100,000-character content ceiling) as the backstop for any writer that is not the API. The content
cap is a `CHECK` rather than a `varchar` because it mirrors the configurable
`ingest.max-content-length`. `SchemaConstraintTest` proves all of it with raw SQL that bypasses the
API.

## Authentication

Setting `API_KEY` turns on an `X-API-Key` filter on every endpoint except the health probes, the
API documentation, and the console page at `/` — the page itself holds no data; everything it
fetches still goes through the filter. Unset — the compose default — the filter does nothing, so
running the project locally needs no credentials. This is a shared secret for a demo API, not a user identity system;
real multi-tenancy would scope every query by the authenticated advisor's organisation, which is a
`WHERE` clause through `client_id` rather than a new subsystem.

## The image is layered

The runtime stage assembles Boot's extracted layers instead of copying the fat jar, so the ~110 MB
of dependencies live in their own image layer and a source change re-pushes about 1 MB. CI publishes
the multi-arch image on every push to main with SBOM and provenance attestations, which is what
`docker compose pull` fetches. Publishing it by hand:

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

The prebuilt image means nobody has to build to try the project. `docker compose up` on its own
still builds locally, so the repository never depends on the registry being reachable.

## Deployment

The live instance runs on a Hetzner Cloud VPS. Postgres 18 or later is required, since the schema
uses the native `uuidv7()`, and pgvector 0.7.0 or later for `sparsevec` — the compose files pin
`pgvector/pgvector:0.8.6-pg18`, the version the measurements were made against, rather than the
floating tag; the migrations create `pgvector` and `pg_trgm` on first start. Provision
with the `hcloud` CLI (`hcloud context create`, or `HCLOUD_TOKEN` in the environment):

```bash
ssh-keygen -t ed25519 -f ~/.ssh/advisor_hetzner -N ''
hcloud ssh-key create --name advisor-hetzner --public-key-from-file ~/.ssh/advisor_hetzner.pub
hcloud firewall create --name advisor-fw
hcloud firewall add-rule advisor-fw --direction in --protocol tcp --port 22  --source-ips 0.0.0.0/0 --source-ips ::/0
hcloud firewall add-rule advisor-fw --direction in --protocol tcp --port 80  --source-ips 0.0.0.0/0 --source-ips ::/0
hcloud firewall add-rule advisor-fw --direction in --protocol tcp --port 443 --source-ips 0.0.0.0/0 --source-ips ::/0
hcloud server create --name advisor-search --type cpx32 --image ubuntu-24.04 --location hel1 \
  --ssh-key advisor-hetzner --firewall advisor-fw
```

The firewall is Hetzner's, not `ufw` on the box: Docker programs its own iptables rules, which
bypass host firewalls, so published ports must be filtered before they reach the machine. On the
server, as root, install Docker and generate the two secrets:

```bash
curl -fsSL https://get.docker.com | sh
mkdir -p /opt/advisor-search
printf 'POSTGRES_PASSWORD=%s\nAPI_KEY=%s\n' "$(openssl rand -hex 24)" "$(openssl rand -hex 24)" \
  > /opt/advisor-search/.env
```

Both secrets are interpolated with `:?`, so a stack started without that `.env` stops with the
missing variable named instead of coming up. That matters most for `API_KEY`: an empty key is how
authentication is switched off locally, so substituting one here would serve client records to the
internet unauthenticated, and the failure would look exactly like a working deployment.

Then, from a checkout of this repository, copy up the production compose file and the Caddyfile and
start the stack. [`deploy/docker-compose.prod.yml`](../deploy/docker-compose.prod.yml) is the local
compose with seven deliberate differences, listed and justified in its header:

```bash
scp deploy/docker-compose.prod.yml root@<ip>:/opt/advisor-search/docker-compose.yml
scp deploy/Caddyfile root@<ip>:/opt/advisor-search/Caddyfile
ssh root@<ip> 'cd /opt/advisor-search && docker compose up -d'
```

TLS lives in a Caddy sidecar, not in Spring: the certificate lifecycle stays out of the JVM and
costs no code. The certificate is a Let's Encrypt IP-address certificate — short-lived by design
(~6 days, renewed in-process at two-thirds of lifetime) — which is also why the proxy is Caddy
rather than nginx plus a certbot cron: at that cadence, renewal has to be unattended and in one
battle-tested process, not a timer signalling a reload across containers.

Both generated secrets live only in `/opt/advisor-search/.env` on the server; `cat` it to read the
API key back. No registry login is needed, because the image is public. The deployed instance runs
without the seed profile, so it starts empty and reports ready in seconds; a seeded instance embeds
the demo corpus first and takes ~1–2 minutes (poll `/actuator/health/readiness`). The corpus can be
loaded afterwards instead, from the console or `POST /demo-corpus` — see
[Seeding on demand](#seeding-on-demand). Teardown when the review window closes:

```bash
hcloud server delete advisor-search
hcloud firewall delete advisor-fw && hcloud ssh-key delete advisor-hetzner
```

## Scaling ladder

This system is stage one of three, and each stage's trigger is named rather than guessed. Stage
one — the current corpus scale — is the exact scan and in-process embedding measured above. Stage
two, from roughly 50k chunks: the HNSW indexes whose DDL and caveats are
[above](#there-is-deliberately-no-ann-index) — the sparse one capped at 1,000 non-zeros a row — a
floor recalibration, async ingest (its p99 criterion has already tripped at the content cap, see
[Ingest is synchronous](#ingest-is-synchronous)), and vector quantization near the top end. Stage three, tens of millions of rows and
up, starts with the domain answer rather than infrastructure: advisor search is tenant-scoped, so
partitioning by organisation prunes a 100-million-row table to one advisor's book per query — after
which each partition is back at a scale this design handles. Only genuinely global search forces
dedicated engines (BM25 in OpenSearch, a vector store or pgvectorscale) and a batched GPU embedding
service; at that point the request profile inverts from CPU-bound to I/O-bound and virtual threads
turn on with one property (below). You scale by changing the retrieval architecture, not by swapping
the web framework.

## Runtime and data-access choices

Four questions a Kotlin service in 2026 is expected to have an answer for, and the answers this one
has.

- **Virtual threads.** The 2026 default for a blocking Spring MVC app — deliberately off here.
  JDK 24 removed pinning on `synchronized` (JEP 491), which killed the classic objection, but a JNI
  downcall still pins its carrier with no scheduler compensation, and this request *is* a JNI
  downcall: ~90% of search latency is ONNX inference. On the deploy's four shared vCPUs a handful
  of in-flight searches pin every carrier — the probe-starvation risk shrinks with core count but
  does not go away — and Tomcat's bounded pool is currently the service's only admission control.
  The flag is one line; what it would optimize — waiting — is not what this service does. The
  reversal trigger is stage three of the scaling ladder above: embedding moves out of process, the
  profile inverts to I/O-bound, the property goes on.
- **WebFlux / coroutines.** Same root sentence: reactive converts waiting into suspension, and
  there is almost no waiting here (~2–3 ms of JDBC in a 24–50 ms request; capacity is bounded by
  inference FLOPs regardless of dispatcher). Going reactive would force an R2DBC rewrite of the
  data layer — no `JdbcClient` equivalent, a hand-written codec for the `vector` type, and Flyway
  still needs the JDBC driver, so two drivers and two transaction models — while the blocking JNI
  inference gets wrapped back onto a thread pool anyway. Suspending controllers are WebFlux-only,
  so there is no coroutines-on-MVC middle path. Post-virtual-threads, reactive earns its keep in
  streaming and high-fan-out services; this is neither.
- **An ORM, Spring Data, or jOOQ.** `JdbcClient` is Spring's 2023 API for exactly this shape of
  service — the SQL is the product here (a per-document KNN aggregate, `ts_headline`,
  `word_similarity`, a transaction-local `set_config`), and every abstraction degenerates to
  native-SQL strings for these queries while adding machinery for the two trivial inserts. jOOQ is
  the escalation path if this grew to dozens of typed queries; Spring Data JDBC is the addition if
  CRUD aggregates ever dominate. Both are additions, not rewrites.
- **A CDS/AOT training run in the image.** Needs a live database inside `docker build` (Flyway
  migrates during context refresh), breaks the `$BUILDPLATFORM` multi-arch design by forcing the
  training pass under QEMU, and would shave ~1 s off a 20 s number — a cost paid once per deploy
  or reboot on a VPS where the JVM otherwise just stays up.

## Operational scope

Two more things deliberately not built.

- **Elasticsearch.** A second stateful service to run and keep in sync, for no recall this corpus
  can demonstrate. Postgres already has both retrieval modes.
- **Update and delete, pagination, synonym dictionaries, Prometheus metrics.** Not needed to
  demonstrate search. Metrics in particular are two Micrometer timers through the actuator that is
  already present — and if they are ever added, the one thing not to do is tag them with the query
  string: `q` is unbounded user input over a corpus of client PII, so a `query` tag is a cardinality
  explosion in the metrics backend and a data-protection problem at once.

## File organisation

Two rules: no type is nested inside another, and a file named after a service, component, repository
or test holds behaviour only — its result and parameter types live beside it, not inside it. Data
types may share a file when they are one cohesive group: `SearchDtos.kt` holds the `SearchHit`
hierarchy, `Corpus.kt` the three records of one JSON document, `Documents.kt` the document DTOs.
That grouping is what the Kotlin conventions call for — "placing multiple declarations in the same
Kotlin source file is encouraged as long as these declarations are closely related to each other
semantically, and the file size remains reasonable" (largest here: 59 lines) — while
one-public-class-per-file is a Java rule (Google Java Style §3.4.1) that Kotlin deliberately did not
adopt, and that neither ktlint nor detekt enforces.
