# Advisor Search

[![build](https://github.com/viusiumbeli/advisor-search/actions/workflows/build.yml/badge.svg)](https://github.com/viusiumbeli/advisor-search/actions/workflows/build.yml)

A search API over clients and their documents, built for the two cases in the brief: a fragment of
an email domain finds its client, and "address proof" finds documents that can evidence an address
even when they never use either word.

Clients are matched with trigrams over name, email and description. Documents are matched twice —
once by Postgres full-text search, once by embedding similarity — and the two rankings are combined
with reciprocal rank fusion. Everything runs in two containers, and the embedding model is baked
into the image and runs in-process, so there is no third-party API to sign up for, nothing external
to be down, and no per-query cost.

Kotlin 2.4 · Spring Boot 4.1 · Postgres 18 with `pgvector` and `pg_trgm` · `all-MiniLM-L6-v2` on
ONNX Runtime.

---

## Live demo

Open <https://95.217.189.232/> — an advisor console over the whole API. The instance starts
empty, so the data you post is all it holds; the seeded tour with the brief's examples is one
`docker compose up` away locally. The console asks once for the API key, which is in the
submission email and deliberately not committed. The same key works against the API directly:

```bash
curl -sG https://95.217.189.232/search \
  -H "X-API-Key: $KEY" --data-urlencode 'q=AldgateWealth'
```

Swagger UI at <https://95.217.189.232/swagger-ui.html> and the health probes need no key. A Caddy
sidecar terminates TLS with a short-lived Let's Encrypt IP certificate; the service speaks plain
HTTP behind it.

---

## Quickstart

```bash
docker compose pull        # optional: a prebuilt amd64/arm64 image, so nothing is compiled locally
docker compose up          # ~20s from clean to healthy; builds the image first if it was not pulled
./demo.sh                  # a guided tour of every endpoint (needs jq)
```

The console comes up on <http://localhost:8080/> with Swagger UI at
<http://localhost:8080/swagger-ui.html>, and needs no credentials locally. It starts under the
`seed` profile, which loads a demo corpus of **10 clients and 20 documents** — the three longest are
around 2,500 words each — through the ordinary `POST` endpoints, so everything you can search was
chunked and embedded by exactly the code path a real ingest uses. Seeding is idempotent, so restarts
do not duplicate it. To start empty and post your own data instead:
`SPRING_PROFILES_ACTIVE= docker compose up` — your clients and documents go through the same
endpoints either way.

---

## Example queries and responses

### A fragment of an email domain finds the client

```console
$ curl -sG localhost:8080/search --data-urlencode 'q=AldgateWealth' | jq '.[0]'
{
  "type": "client",
  "score": 1.0,
  "matched_on": "email",
  "client": {
    "id": "878de338-32de-4d2b-8158-ca55dac2a48b",
    "first_name": "Jane",
    "last_name": "Roe",
    "email": "jane.roe@aldgatewealth.example",
    "description": "Founder and managing director of a boutique advisory practice. …",
    "social_links": ["https://links.example/in/jane-roe", "…"]
  }
}
```

### "address proof" finds documents that never say it

```console
$ curl -sG localhost:8080/search --data-urlencode 'q=address proof' \
    | jq -r '.[] | "\(.matched_on)\t\(.document.title)"'
keyword     Onboarding Checklist: Identity and Address Verification
semantic    Bank Statement, Current Account, March
semantic    Assured Shorthold Tenancy Summary, 22 Rookery Lane
semantic    Electricity Account Statement, 14 Marlow Court
```

Four results, and all four can evidence where a client lives. Only the first contains the words
"address" and "proof"; the other three are reached by meaning alone. The electricity bill mentions
neither word anywhere — it talks about meter readings, unit rates and direct debits — and getting it
into that list was the hardest part of the task, written up in
[why the task's own example needs more than a model](docs/search-design.md#why-the-tasks-own-example-needs-more-than-a-model).

`./demo.sh` runs both of these plus every other endpoint against a local stack.

---

## API

| Method | Path | Notes |
| --- | --- | --- |
| `POST` | `/clients` | `201` + `Location`. `400` with per-field messages, `409` on a duplicate email. |
| `GET` | `/clients/{id}` | The client, or `404`. |
| `POST` | `/clients/{id}/documents` | `201` + `Location`. Chunks and embeds before responding, so the document is searchable immediately. `404` for an unknown client, `400` above 100,000 characters. |
| `GET` | `/documents/{id}` | The document including its full content, or `404`. |
| `GET` | `/documents/{id}/summary` | Extractive summary: the passages nearest the document's own centroid, in reading order. |
| `GET` | `/search?q=&limit=` | The search. `q` is required and must be non-blank; `limit` defaults to 10 and is clamped to 50. |
| `GET` | `/actuator/health` | Liveness and readiness. Readiness waits for the embedding model. |

Live OpenAPI at `/swagger-ui.html`; the console at `/` drives every endpoint from a browser.
Field names follow the task's fragment (`first_name`,
`client_id`, `created_at`), so the whole API speaks snake_case. Errors are RFC 9457
`application/problem+json`, and validation failures carry an extra `errors` object keyed by field,
because "400 Bad Request" alone does not tell a caller which of five fields it got wrong.

`GET /search` returns one flat array, as the fragment specifies, ordered in two blocks: every client
hit, then every document hit. **Scores are comparable within a block, not between blocks** — a
client score is a trigram similarity in 0..1, a document score is a reciprocal rank fusion weight of
around 0.016. Sorting them into a single sequence would be comparing two different measurements, so
the blocks are never interleaved. Document hits carry a `snippet` and the document's metadata but
not its `content`: a page of hits should not carry several 2,500-word documents, and the full text
is one `GET /documents/{id}` away.

### Extensions to the given schema

Three additions, each because the fragment specifies a shape rather than a whole API:

- **`409` on a duplicate email.** Advisors search by email, which makes it the identity key.
- **The `GET` endpoints.** A `201` with a `Location` header and search results with snippets are
  both dangling without a way to fetch the thing they point at.
- **`score`, `matched_on` and `snippet` on search hits.** The fragment types search results as
  `type: object`, and a result you cannot explain is hard to trust. `matched_on` is `email`, `name`,
  `description` or `profile` for clients, and `keyword`, `semantic` or `both` for documents.

For a fuzzy client match `matched_on` is deliberately `profile` rather than a field name: the
similarity is computed over the whole profile, so no single field is responsible and naming one
would be an invention.

---

## How search works

**Clients use trigrams, not full-text search.** Postgres's English parser classifies
`jane.roe@aldgatewealth.example` as an `email` token and emits it as a single lexeme, so no
full-text query for a fragment of the domain can match inside it and the brief's first example would
fail. `FtsEmailTokenisationProofTest` pins that behaviour. Instead a generated `search_text` column
carries the lowercased name, email and description under one `gin_trgm_ops` index, queried through
two arms — a literal substring match scoring 1.0, and `word_similarity` for typos.

**Documents get two retrievers.** A stored `tsvector` with `setweight` (title A, content B) finds
rare exact tokens that embeddings treat as noise, like the policy number `PLC-88213`. Alongside it,
documents are chunked to ~200 wordpieces and embedded with `all-MiniLM-L6-v2` as `vector(384)`, and
search reduces the corpus to each document's own nearest chunk and shortlists the best thirty —
counted in documents rather than chunks, so a long report cannot fill the shortlist and crowd a
short one out of it. Because `websearch_to_tsquery` combines terms with AND, the lexical arm
legitimately returns nothing for many queries — which is why the two arms are unioned, not joined.

**The rankings are fused, not blended.** `ts_rank_cd` and cosine similarity are on unrelated scales,
so the two lists are combined with reciprocal rank fusion — `1/(k + rank)`, `k = 60` — which
combines rankings rather than scores. Relevance cut-offs are applied *before* fusion, because once a
score has become a rank "not similar enough" is no longer expressible. Clients stay out of the
fusion entirely: a client can never appear in a document ranking, so an exact email match would be
capped at `1/61` and lose to an average document.

**One case needed more than a model.** The brief asks that "address proof" return documents
containing "utility bill". The seeded electricity bill scores 0.484 against "utility bill" but 0.139
against "address proof", where it ranks 13th — and all five sentence-embedding models I measured
ranked it 13th to 18th while ranking every other probe first. "An electricity bill can evidence
where you live" is procedural knowledge about a domain, not a distributional fact about English, so
it is stated explicitly in `src/main/resources/search/query-expansions.json`: five concepts, each
with the phrases that trigger it and the phrases to also search for. A matching query runs as
several probes — capped at five, embedded as one batch, semantic arm only — and each document keeps
its best score across them.

The measurements behind each of these, the SQL, and how every cut-off was calibrated are in
[search design](docs/search-design.md).

---

## Results

Measured on a MacBook Pro (Apple M3 Pro, 12 cores, 36 GB RAM, macOS 26.5.2) with Docker Desktop,
against the seeded corpus of 10 clients, 20 documents and 153 chunks.

`golden-queries.json` holds 25 queries an advisor might type, each with the one result that must
come back; `SearchQualityTest` asserts every one lands in the top five and prints mean reciprocal
rank, so results sliding down the list is visible even while every query still passes.

| | |
| --- | --- |
| Retrieval quality | documents 18/18 hit@5 (MRR 0.778), clients 7/7 (MRR 0.929) |
| `GET /search`, warm | 24 ms median, 50 ms when a query expands to five probes |
| Under concurrency | p95 130 ms at 20 concurrent clients, 188 req/s |
| Ingest | 269 ms for a 10 KB document, 2.3 s at the 100,000-character cap |
| Exact vector scan | the right choice to ~50,000 chunks, where an HNSW index starts to earn its keep |
| Startup and tests | healthy 20 s after `docker compose up`; 87 tests from clean in 39 s |

Sustained-load tables across three corpus scales, and the ceilings the system runs against, are in
[load and limits](docs/load-and-limits.md); the design notes behind these numbers are in
[operating notes](docs/operating-notes.md).

---

## Deploying it

The live instance runs on a Hetzner Cloud VPS (`cpx32`, 4 vCPU / 8 GB — the JVM plus ONNX Runtime's
native arenas need a 2 GB floor, and 256 MB OOMs) from
[`deploy/docker-compose.prod.yml`](deploy/docker-compose.prod.yml): the published image beside a
`pgvector/pgvector:pg18` container, both secrets read from a server-side `.env`, and no public
Postgres port. Provisioning steps are in
[operating notes](docs/operating-notes.md#deployment).

---

## Decisions, and what I left out

Each of these was considered and deliberately not built.

- **Generative summaries.** The summary is extractive, so it cannot invent a fact about a client's finances ([detail](docs/search-design.md#scope-what-search-deliberately-does-not-do)).
- **Semantic search over client descriptions.** "retired educator" will not find a "retired teacher"; the brief's client example is lexical.
- **A cross-encoder re-ranker.** The principled fix for the floor-calibration overlap, at ~50 ms per query ([detail](docs/search-design.md#calibrating-the-cut-offs)).
- **An ANN index.** An exact scan cannot miss a neighbour, and at this size it is not the bottleneck ([thresholds](docs/operating-notes.md#there-is-deliberately-no-ann-index)).
- **Asynchronous ingest.** A `201` currently means searchable ([the p99 that would change it](docs/operating-notes.md#ingest-is-synchronous)).
- **One ranked list across clients and documents.** The scales are not comparable, hence two blocks.

Elasticsearch, `unaccent`, `social_links`, virtual threads, WebFlux, an ORM and a CDS/AOT training
run were considered too; the reasoning is in
[operating notes](docs/operating-notes.md#runtime-and-data-access-choices) and
[search design](docs/search-design.md#scope-what-search-deliberately-does-not-do).

---

## Development

```bash
./gradlew build          # compiles, runs ktlint and the full suite (needs Docker for Testcontainers)
./gradlew ktlintFormat   # apply formatting
./gradlew bootRun        # against a local Postgres; provisions the model first if needed
```

Requires Docker; the JDK does not need to be installed — the toolchain resolver provisions JDK 25
automatically on the first build. Gradle's configuration cache is on, so repeat invocations skip
configuration entirely. Integration tests share one pgvector container through a cached Spring
context — without that sharing the suite would start Postgres once per test class — and only the API
key test, which changes properties, gets a context and container of its own. CI builds every push
and pull request (lint, full suite against real Postgres, image build), with the model cached under
its committed checksum key so huggingface.co is not on the critical path; pushes to main publish the
multi-arch image that `docker compose pull` fetches, with SBOM and provenance attestations.

Layout: `embedding/` is the tokenizer, ONNX encoder and chunker; `clients/` and `documents/` are the
write path; `search/` holds the three retrievers, with `search/ranking/` for reciprocal rank fusion
and `search/expansion/` for the domain lexicon; `seed/` loads the demo corpus (`seed/corpus/`)
through the real service layer. The two conventions the layout follows are in
[operating notes](docs/operating-notes.md#file-organisation).
