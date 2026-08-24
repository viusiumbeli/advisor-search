# Advisor Search

[![build](https://github.com/viusiumbeli/advisor-search/actions/workflows/build.yml/badge.svg)](https://github.com/viusiumbeli/advisor-search/actions/workflows/build.yml)

A search API over clients and their documents. Clients are matched with trigrams over name, email
and description; documents are matched twice, once by Postgres full-text search and once by
embedding similarity, and the two rankings are combined with reciprocal rank fusion.

Everything runs in two containers. The embedding model is baked into the image and executes
in-process, so there is no API key to obtain, no external service to be down, and no per-query cost.

This page is the whole system in outline. The measurements and the reasoning behind each choice are
in [search design](docs/search-design.md), [operating notes](docs/operating-notes.md) and
[load and limits](docs/load-and-limits.md).

---

## Quickstart

```bash
docker compose pull        # optional: a prebuilt amd64/arm64 image, so nothing is compiled locally
docker compose up          # ~20s from clean to healthy; builds the image first if it was not pulled
./demo.sh                  # a guided tour of every endpoint (needs jq)
```

The API comes up on <http://localhost:8080> with Swagger UI at
<http://localhost:8080/swagger-ui.html>. It starts under the `seed` profile, which loads a demo
corpus of **10 clients and 20 documents** — the three longest are around 2,500 words each — through
the ordinary `POST` endpoints, so everything you can search was chunked and embedded by exactly the
code path a real ingest uses. Seeding is idempotent, so restarts do not duplicate it.

### The task's first example

A fragment of an email domain finds the client:

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

### The task's second example

"address proof" returns every document that could evidence an address, including an electricity
bill that contains neither word:

```console
$ curl -sG localhost:8080/search --data-urlencode 'q=address proof' \
    | jq -r '.[] | "\(.matched_on)\t\(.document.title)"'
keyword     Onboarding Checklist: Identity and Address Verification
semantic    Bank Statement, Current Account, March
semantic    Assured Shorthold Tenancy Summary, 22 Rookery Lane
semantic    Electricity Account Statement, 14 Marlow Court
```

Four results, and all four are documents that can evidence where a client lives. Only the first
contains the words "address" and "proof"; the other three are reached by meaning alone. The
electricity bill mentions neither word anywhere — it talks about meter readings, unit rates and
direct debits — and getting it into that list turned out to be the hardest part of the task, written
up in [Why the task's own example needs more than a model](docs/search-design.md#why-the-tasks-own-example-needs-more-than-a-model).

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

Field names follow the task's OpenAPI fragment (`first_name`, `client_id`, `created_at`), so the
whole API speaks snake_case. Errors are RFC 9457 `application/problem+json`, and validation failures
carry an extra `errors` object keyed by field, because "400 Bad Request" alone does not tell a
caller which of five fields it got wrong.

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

### Clients: trigrams, not full-text search

Postgres's English parser classifies `jane.roe@aldgatewealth.example` as an **`email` token and
emits it as a single lexeme**, so no full-text query for a fragment of the domain can match inside
it and the task's own first example would fail. This is not an assertion in a README — it is pinned
by `FtsEmailTokenisationProofTest`:

```sql
SELECT to_tsvector('english', 'jane.roe@aldgatewealth.example');
-- 'jane.roe@aldgatewealth.example':1        one lexeme, not five
```

So clients are indexed with `pg_trgm` over a generated `search_text` column holding the lowercased
name, email and description, and searched through two arms in one statement:

```sql
WHERE search_text LIKE '%' || :query || '%' ESCAPE '\'   -- exact substring
   OR :query <% search_text                              -- word similarity, for typos
```

One `gin_trgm_ops` index serves both, combined by the planner into a
[`BitmapOr`](docs/operating-notes.md#the-client-query-uses-its-index-at-scale), and the score is
`GREATEST(substring ? 1 : 0, word_similarity(...))`, so a literal containment always scores exactly
1.0. `word_similarity` and not `similarity`, because whole-string similarity for `aldgatewealth`
collapses to 0.094 over a full profile while word similarity stays at 1.0 — [that measurement, the
`translate()` objection it answers, and the short-query rule](docs/search-design.md#clients-trigrams-not-full-text-search).

### Documents: two retrievers

**Lexical.** A stored `tsvector` with `setweight` (title A, content B), queried with
`websearch_to_tsquery` and ranked with `ts_rank_cd`. This is what finds rare exact tokens that
embeddings treat as noise — `PLC-88213` returns its policy schedule as a `keyword` hit. Because
`websearch_to_tsquery` combines terms with **AND**, this arm legitimately returns nothing for many
real queries, which is why fusion has to be a union: an inner join would have silently deleted the
task's second example.

**Semantic.** Documents are chunked to ~200 wordpieces with ~30 of overlap, each chunk is embedded
with `all-MiniLM-L6-v2` on ONNX Runtime in-process and stored as `vector(384)`, and search takes the
globally nearest chunks and collapses them to one best chunk per document. The chunker measures
every size decision with the same tokenizer the encoder uses and falls back to a hard window,
because an unbroken block longer than the window would otherwise reach the encoder and be silently
truncated.

Both retrievers in full — the SQL, the pooling and L2-normalisation detail, and the two traps that
shaped the chunker — are in [Documents: two retrievers](docs/search-design.md#documents-two-retrievers).

### Fusion

The two document rankings are combined with reciprocal rank fusion — `1/(k + rank)`, `k = 60` —
from Cormack, Clarke and Buettcher (SIGIR 2009), the default hybrid combiner in Elasticsearch,
OpenSearch and Azure AI Search. It combines *rankings* rather than scores, which is the point:
`ts_rank_cd` and cosine similarity are on unrelated scales, and no fixed weighting between them
survives a change of corpus. Two consequences shaped the code — relevance cut-offs are applied
*before* fusion, because once a score has become a rank "not similar enough" is no longer
expressible; and clients are not fused at all, because a client can never appear in a document
ranking and an exact email match would be permanently capped at `1/61`. The tie-breaking rule and
the reordering bug that forced it are in [Fusion](docs/search-design.md#fusion).

### Query expansion

The task asks that "address proof" return documents containing "utility bill", and no model does
that on its own: the seeded electricity bill scores **0.484** against "utility bill" but **0.139**
against "address proof", where it ranks 13th, and all five sentence-embedding models I measured
ranked it between 13th and 18th while ranking every other probe first. "An electricity bill can
evidence where you live" is procedural knowledge about a domain, not a distributional fact about
English, so it is stated explicitly in `src/main/resources/search/query-expansions.json`: five
concepts, each with the phrases that trigger it and the phrases to also search for. A matching query
runs as several probes — capped at five, embedded as one batch, semantic arm only — and each
document keeps its best score across them. That is what puts the electricity bill in the result list
at the top of this page. The bake-off table, the cost, and the limits of a hand-maintained lexicon
are in [Why the task's own example needs more than a model](docs/search-design.md#why-the-tasks-own-example-needs-more-than-a-model).

---

## Numbers that matter

All measured on this machine, none estimated; every row links to the full analysis.

| | | |
| --- | --- | --- |
| Search, seed corpus, warm | 24 ms median, 50 ms with expansion probes | [detail](docs/operating-notes.md#measured-on-this-machine) |
| Search, 20 concurrent clients | p95 130 ms at 188 req/s; latency grows linearly with chunk count | [detail](docs/load-and-limits.md#search-under-load) |
| Ingest, one 10 KB document | 269 ms, 10 chunks — cost tracks document size, not corpus size | [detail](docs/load-and-limits.md#ingest-under-load) |
| Ingest at the cap, 99 KB | 1.7 s, 95 chunks, synchronous, so it is what a caller's timeout must cover | [detail](docs/operating-notes.md#ingest-is-synchronous) |
| Exact vector scan crossover | ~50,000 chunks, where an HNSW index starts to earn its keep | [detail](docs/operating-notes.md#there-is-deliberately-no-ann-index) |
| `content` ceiling | 100,000 characters: 6× under the tsvector lexeme limit and right on the 16,383-position ranking ceiling | [detail](docs/load-and-limits.md#limits) |
| Retrieval quality | hit@5 18/18 documents (MRR 0.778) and 7/7 clients (MRR 0.929) over 25 golden queries | [detail](docs/search-design.md#evaluation) |
| Startup and tests | `docker compose up` healthy in 20 s; 66 tests from clean in 27 s | [detail](docs/operating-notes.md#measured-on-this-machine) |

---

## Deploying it

The demo runs on a Hetzner Cloud VPS: the published image and a `pgvector/pgvector:pg18` container
beside it. Postgres 18 or later is required, since the schema uses the native `uuidv7()`; the
migration creates `pgvector` and `pg_trgm` on first start. Provision with the `hcloud` CLI
(`hcloud context create`, or `HCLOUD_TOKEN` in the environment):

```bash
ssh-keygen -t ed25519 -f ~/.ssh/advisor_hetzner -N ''
hcloud ssh-key create --name advisor-hetzner --public-key-from-file ~/.ssh/advisor_hetzner.pub
hcloud firewall create --name advisor-fw
hcloud firewall add-rule advisor-fw --direction in --protocol tcp --port 22   --source-ips 0.0.0.0/0 --source-ips ::/0
hcloud firewall add-rule advisor-fw --direction in --protocol tcp --port 8080 --source-ips 0.0.0.0/0 --source-ips ::/0
hcloud server create --name advisor-search --type cpx32 --image ubuntu-24.04 --location hel1 \
  --ssh-key advisor-hetzner --firewall advisor-fw
```

The firewall is Hetzner's, not `ufw` on the box: Docker programs its own iptables rules, which
bypass host firewalls, so published ports must be filtered before they reach the machine. Size for
a [2 GB memory floor](docs/load-and-limits.md#limits). On the server, as root, install Docker and
generate the two secrets:

```bash
curl -fsSL https://get.docker.com | sh
mkdir -p /opt/advisor-search
printf 'POSTGRES_PASSWORD=%s\nAPI_KEY=%s\n' "$(openssl rand -hex 24)" "$(openssl rand -hex 24)" \
  > /opt/advisor-search/.env
```

Then, from a checkout of this repository, copy up the production compose file and start the stack.
[`deploy/docker-compose.prod.yml`](deploy/docker-compose.prod.yml) is the local compose with five
deliberate differences, listed and justified in its header:

```bash
scp deploy/docker-compose.prod.yml root@<ip>:/opt/advisor-search/docker-compose.yml
ssh root@<ip> 'cd /opt/advisor-search && docker compose up -d'
```

Both generated secrets live only in `/opt/advisor-search/.env` on the server; `cat` it to read the
API key back. No registry login is needed, because the image is public — publishing it is covered in
[operating notes](docs/operating-notes.md#the-image-is-layered). First boot needs patience: the
instance embeds the demo corpus before reporting ready (~1–2 minutes; poll
`/actuator/health/readiness`). Teardown when the review window closes:

```bash
hcloud server delete advisor-search
hcloud firewall delete advisor-fw && hcloud ssh-key delete advisor-hetzner
```

---

## Decisions, and what I left out

Everything below was considered and deliberately not built. Each line says why, and what adding it
would look like.

- **Generative summaries.** The summary is extractive: the passages closest to the document's own
  centroid (`avg(embedding)` in pgvector), returned in reading order, so every sentence is verbatim
  from the document. It needs no second model and cannot invent a fact about a client's finances;
  a generative version is an isolated swap behind the same endpoint.
- **Semantic search over client descriptions.** "retired educator" will not find a client described
  as a "retired teacher", because clients are matched lexically. Descriptions are one short field
  and the task's client example is lexical, so this is the same chunk-and-embed pipeline pointed at
  a second table — a named extension rather than a gap I missed.
- **A cross-encoder re-ranker.** The principled fix for the calibration overlap in
  [Calibrating the cut-offs](docs/search-design.md#calibrating-the-cut-offs).
- **One ranked list across clients and documents.** The scales are not comparable — a decision, not
  an omission; see the two blocks under [API](#api).
- **An ANN index.** [Thresholds measured](docs/operating-notes.md#there-is-deliberately-no-ann-index).
- **Asynchronous ingest.** [Transition criterion named](docs/operating-notes.md#ingest-is-synchronous).
- **Elasticsearch.** A second stateful service to run and keep in sync, for no recall this corpus
  can demonstrate. Postgres already has both retrieval modes.
- **Searching `social_links`.** `array_to_string` is `STABLE`, not `IMMUTABLE`, so it cannot go in
  the generated column. The escape hatch is an `IMMUTABLE` wrapper plus
  `ALTER TABLE … ALTER COLUMN … SET EXPRESSION AS` (PostgreSQL 17+).
- **`unaccent`.** The corpus is English; accented names are reachable by trigram similarity. Adding
  it means an `unaccent`-based `IMMUTABLE` wrapper in the generated column.
- **Partial tokens inside document content.** "PLC-88" will not find `PLC-88213`; a trigram index on
  content would fix it at the cost of a second large index.
- **Update and delete, pagination, synonym dictionaries, Prometheus metrics.** Not needed to
  demonstrate search. Metrics in particular are two Micrometer timers through the actuator that is
  already present — and if they are ever added, the one thing not to do is tag them with the query
  string: `q` is unbounded user input over a corpus of client PII, so a `query` tag is a cardinality
  explosion in the metrics backend and a data-protection problem at once.
- **Virtual threads.** The 2026 default for a blocking Spring MVC app — deliberately off here,
  because a JNI downcall still pins its carrier with no scheduler compensation and this request *is*
  a JNI downcall: ~90% of search latency is ONNX inference.
  [Full reasoning and the reversal trigger](docs/operating-notes.md#runtime-and-data-access-choices).
- **WebFlux / coroutines.** Same root sentence: reactive converts waiting into suspension, and there
  is almost no waiting here (~2–3 ms of JDBC in a 24–50 ms request).
  [Why an R2DBC rewrite would not pay](docs/operating-notes.md#runtime-and-data-access-choices).
- **An ORM, Spring Data, or jOOQ.** `JdbcClient` is Spring's 2023 API for exactly this shape of
  service — the SQL is the product here, and every abstraction degenerates to native-SQL strings for
  these queries. [Escalation paths](docs/operating-notes.md#runtime-and-data-access-choices).
- **A CDS/AOT training run in the image.** Needs a live database inside `docker build`, breaks the
  `$BUILDPLATFORM` multi-arch design, and would shave ~1 s off a 20 s number.
  [Detail](docs/operating-notes.md#runtime-and-data-access-choices).

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
context; only the API key test, which changes properties, gets a context and container of its own.
Without that sharing the suite would start Postgres once per test class.

CI builds every push and pull request (lint, full suite against real Postgres, image build), with
the model cached under its committed checksum key so huggingface.co is not on the critical path.
Pushes to main publish the multi-arch image that `docker compose pull` fetches, with SBOM and
provenance attestations.

Layout: `embedding/` is the tokenizer, ONNX encoder and chunker; `clients/` and `documents/` are the
write path; `search/` holds the three retrievers, with `search/ranking/` for reciprocal rank fusion
and `search/expansion/` for the domain lexicon; `seed/` loads the demo corpus (`seed/corpus/`)
through the real service layer.

On file organisation, two rules: no type is nested inside another, and a file named after a service,
component, repository or test holds behaviour only — its result and parameter types live beside it,
not inside it. Data types may share a file when they are one cohesive group: `SearchDtos.kt` holds
the `SearchHit` hierarchy, `Corpus.kt` the three records of one JSON document, `Documents.kt` the
document DTOs. That grouping is what the Kotlin conventions call for — "placing multiple declarations
in the same Kotlin source file is encouraged as long as these declarations are closely related to
each other semantically, and the file size remains reasonable" (largest here: 59 lines) — while
one-public-class-per-file is a Java rule (Google Java Style §3.4.1) that Kotlin deliberately did not
adopt, and that neither ktlint nor detekt enforces.
