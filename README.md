# Advisor Search

A search API over clients and their documents. Clients are matched with trigrams over name, email
and description; documents are matched twice, once by Postgres full-text search and once by
embedding similarity, and the two rankings are combined with reciprocal rank fusion.

Everything runs in two containers. The embedding model is baked into the image and executes
in-process, so there is no API key to obtain, no external service to be down, and no per-query cost.

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
up in [Why the task's own example needs more than a model](#why-the-tasks-own-example-needs-more-than-a-model).

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

Request and response field names follow the task's OpenAPI fragment (`first_name`, `client_id`,
`created_at`), so the whole API speaks snake_case. Errors are RFC 9457 `application/problem+json`;
validation failures carry an extra `errors` object keyed by field, because "400 Bad Request" alone
does not tell a caller which of five fields it got wrong.

`GET /search` returns one flat array, as the fragment specifies, ordered in two blocks: every client
hit, then every document hit. **Scores are comparable within a block, not between blocks.** A client
score is a trigram similarity in 0..1; a document score is a reciprocal rank fusion weight of around
0.016. Sorting them into a single sequence would be comparing two different measurements, so the
blocks are never interleaved.

Document hits carry a `snippet` and the document's metadata, but not its `content`. A page of hits
should not carry several 2,500-word documents; the full text is one `GET /documents/{id}` away.

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
emits it as a single lexeme**. No full-text query for a fragment of the domain can match inside it,
so the task's own first example would fail. This is not an assertion in a README — it is pinned by
`FtsEmailTokenisationProofTest`:

```sql
SELECT to_tsvector('english', 'jane.roe@aldgatewealth.example');
-- 'jane.roe@aldgatewealth.example':1        one lexeme, not five

SELECT to_tsvector('english', 'jane.roe@aldgatewealth.example')
           @@ websearch_to_tsquery('english', 'AldgateWealth');
-- f
```

So clients are indexed with `pg_trgm` over a generated `search_text` column holding the lowercased
name, email and description, and searched through two arms in one statement:

```sql
WHERE search_text LIKE '%' || :query || '%' ESCAPE '\'   -- exact substring
   OR :query <% search_text                              -- word similarity, for typos
```

A single `gin_trgm_ops` index serves both, and the planner combines them with a `BitmapOr` (plan
below). The score is `GREATEST(substring ? 1 : 0, word_similarity(...))`, so a literal containment
always scores exactly 1.0.

Measured on the seeded corpus, this is why the arms are shaped that way:

| Expression | Value |
| --- | --- |
| `similarity('jane.roe@aldgatewealth.example', 'AldgateWealth')` | 0.4516 |
| `similarity(search_text, 'aldgatewealth')` — the whole profile | **0.0940** |
| `word_similarity('aldgatewealth', search_text)` | **1.0000** |
| `word_similarity('delacroix-whitfeld', search_text)` — a misspelled surname | 0.8095 |

Whole-string similarity collapses to 0.094 once the email is one field among several, because the
query's trigrams are diluted by everything else in the profile. `word_similarity` compares against
the best-matching extent instead of the whole string, which is what keeps it at 1.0. Getting this
wrong is easy: the email *in isolation* scores 0.45 and looks like it would work.

**The obvious objection.** `to_tsvector('english', translate(email, '@.', '  '))` is `IMMUTABLE`, so
it is legal in a generated column, and it does make full-text search match `aldgatewealth`. That is
tested too — and so is its limit: it still only matches whole tokens, so `AldgateW` and any
misspelling stay unreachable. Trigrams cover substrings, typos, names and descriptions with one
index, so the normalisation buys nothing this design does not already have.

Queries shorter than three alphanumeric characters drop the fuzzy arm and keep only the substring
arm: trigram similarity over one or two characters is noise, and would return an arbitrary slice of
the client list.

### Documents: two retrievers

**Lexical.** A stored `tsvector` with `setweight` (title A, content B), queried with
`websearch_to_tsquery` and ranked with `ts_rank_cd`. This is what finds rare exact tokens that
embeddings treat as noise:

```console
$ curl -sG localhost:8080/search --data-urlencode 'q=PLC-88213' | jq '.[0] | {matched_on, title: .document.title}'
{ "matched_on": "keyword", "title": "Policy Schedule, Whole of Life Cover" }
```

Note that `websearch_to_tsquery` combines terms with **AND**: `'address proof'` becomes
`'address' & 'proof'`. For many real queries this arm legitimately returns nothing, which is why
fusion has to be a union — an inner join would have silently deleted the task's second example.

**Semantic.** Documents are chunked to ~200 wordpieces with ~30 of overlap, each chunk is embedded
with `all-MiniLM-L6-v2` running on ONNX Runtime in-process, and stored as `vector(384)`. Search
takes the globally nearest chunks and collapses them to one best chunk per document:

```sql
SELECT DISTINCT ON (top.document_id) …, 1 - (top.embedding <=> CAST(:vector AS vector)) AS score
FROM (SELECT … FROM document_chunks ORDER BY embedding <=> CAST(:vector AS vector) LIMIT 50) top
JOIN documents d ON d.id = top.document_id
ORDER BY top.document_id, top.embedding <=> CAST(:vector AS vector);
```

The pipeline reproduces the checkpoint's published behaviour exactly: mean pooling over unmasked
tokens, then L2 normalisation. Because the stored vectors are unit length, `1 - (a <=> b)` is
directly the cosine similarity.

**The chunker is measured, not estimated.** Every size decision uses the same tokenizer the encoder
uses, because the budget that matters is wordpieces. It tries paragraph boundaries, then sentence
boundaries, then a hard window — and the fallback is the point: without it, one unbroken block
longer than the window would reach the encoder and be silently truncated. It also keeps the original
whitespace between pieces, so a stored chunk is a true substring of the document and a snippet
quotes the source exactly.

Two traps worth naming, both covered by tests:

- The Spring AI transformers starter would have been the quick route here. Its jar ships a Git LFS
  pointer rather than the model, downloads ~90 MB at JVM start, and its tokenizer truncates at 128
  wordpieces **silently**. Running ONNX Runtime and the tokenizer directly is about 60 lines and
  removes all three surprises.
- WordPiece maps any run over 100 characters to a single unknown token, so a 6,000-character wall of
  text satisfies a wordpiece budget while being a useless chunk. The chunker applies a character
  ceiling as well.

### Why the task's own example needs more than a model

The brief asks that "address proof" return documents containing "utility bill". Measured on this
corpus, the naive version of that does not work — and no larger model fixes it.

The seeded electricity bill never says "address proof", "proof of address", "verification" or
"identity". It talks about meter readings, unit rates and direct debits. Against the query
"utility bill" it is the top result at cosine **0.484**. Against "address proof" it scores **0.139**
and ranks 13th.

I measured five sentence-embedding models on the seeded corpus, each with its own documented pooling
and query prefix, ranking all 20 documents per probe. The rank of the electricity bill:

| Model | "address proof" | "proof of address" | "utility bill" | Other 5 probes |
| --- | --- | --- | --- | --- |
| all-MiniLM-L6-v2 | 13 | 14 | **1** | all rank 1 |
| multi-qa-MiniLM-L6-cos-v1 | 17 | 13 | **1** | all rank 1 |
| msmarco-MiniLM-L6-cos-v5 | 14 | 13 | **1** | all rank 1 |
| e5-small-v2 | 14 | 13 | **1** | all rank 1 |
| bge-small-en-v1.5 | 18 | 17 | **1** | all rank 1 |

Every model ranked every other probe — "retirement income planning", "share options vesting",
"who can act for a client if they lose capacity" and two more — first. All five failed the same
single case in the same way. (`ModelSelectionExperiment` reproduces this; see its header.)

That is not a model quality problem. "An electricity bill can evidence where you live" is
**procedural knowledge about a domain**, not a distributional fact about English, and a model
trained on general text has no way to know it. Reaching for a larger model would have burned the
budget and changed nothing.

So the knowledge is stated explicitly, in `src/main/resources/search/query-expansions.json`: five
concepts, each with the phrases that trigger it and the phrases to also search for. A query matching
a trigger is run as several probes and each document keeps its best score across them. Taking the
maximum rather than blending the probes into one vector matters — averaging "address proof" with
"utility bill" produces a vector that is a weaker match for both than either is alone.

Expansion widens the **semantic arm only**. The lexical arm's value is precision on exact tokens,
and OR-ing extra phrases into it would trade that away for recall the semantic arm already provides.

It is not free: each probe is another embedding and another scan, so an expanded query takes about
66 ms against 24 ms for a plain one. That is why the expander caps a query at five probes, and why
only queries that match a trigger pay anything at all.

This is the honest version of the trade-off, and its limits are worth stating: a hand-maintained
lexicon does not generalise, and it is only as good as the person editing it. In a real product this
knowledge would come from a maintained document taxonomy, or from classifying documents by evidence
type at ingest. What it should *not* come from is hoping a bigger model has it.

### Fusion

The two document rankings are combined with reciprocal rank fusion — `1/(k + rank)`, `k = 60` —
from Cormack, Clarke and Buettcher (SIGIR 2009), and the default hybrid combiner in Elasticsearch,
OpenSearch and Azure AI Search. It combines *rankings* rather than scores, which is the point:
`ts_rank_cd` and cosine similarity are on unrelated scales, and no fixed weighting between them
survives a change of corpus. A document both retrievers rank outscores one that a single retriever
ranks highly, so agreement between lexical and semantic evidence wins. It is 20 lines of Kotlin with
unit tests that check the arithmetic by hand (`1/61`; `1/65 + 1/68`).

Two consequences that shaped the code:

- **Relevance cut-offs must be applied before fusion.** Once a score has become a rank, "not similar
  enough" is no longer expressible, so both arms are filtered while their numbers still exist. See
  [Calibrating the cut-offs](#calibrating-the-cut-offs).
- **Clients are not in the fusion.** A client can never appear in a document ranking, so an exact
  email match would be permanently capped at `1/61` and would lose to an average document that
  happened to appear in both lists. Hence the two blocks.

Exact ties are common — two documents that place equally in different lists score identically — and
are broken by evidence first (a lexical hit is a fact, a semantic hit is an estimate), then by
title. Neither falls back to the primary key: ids are random per install, and an id tie-break makes
results reorder between one deployment and the next. That was a real bug, caught by running the
evaluation twice.

---

## Evaluation

`golden-queries.json` holds 25 queries an advisor might type, each with the one result that must come
back. `SearchQualityTest` asserts every one lands in the top five and prints mean reciprocal rank, so
a change that keeps every query passing while pushing results down the list is still visible.

| Set | Queries | hit@5 | MRR |
| --- | --- | --- | --- |
| Documents | 18 | 18/18 | 0.778 |
| Clients | 7 | 7/7 | 0.929 |

The single client query not at rank 1 is "retired teacher", which ties a retired *teacher* with a
retired *educator*. Both are returned; the tie is genuine.

This is a small hand-built set, and that is its honest limitation: it protects against regressions in
the behaviour I chose, not against being wrong about what advisors actually search for.

### Calibrating the cut-offs

Both retrievers get a relevance floor before fusion, and neither is a single absolute number,
because neither score has a calibrated scale across queries. Both thresholds were measured on this
corpus rather than picked.

**The absolute cosine floor answers "can the corpus answer this at all?"** My first attempt was to
make it a relevance judgement too, and the measurement says that cannot work:

- Documents that genuinely answer their query score from **0.329** upwards.
- Documents that do not reach **0.376**.

**Those ranges overlap, so no single threshold separates them.** The high false positives are all
proper-noun and reference-code queries — "AldgateWealth" (0.376), "raghunathan" (0.340),
"PLC-88213" (0.339) — where the lexical arm is authoritative and the semantic arm has nothing to
add. The low true positives are real conceptual matches: "double taxation treaty" (0.329) reaches a
document that says "double taxation *agreement*". So the absolute floor is set low, at **0.30**, and
does one job: nonsense peaks at 0.21 ("zzzqqq nonsense token") and is rejected outright.

**A relative cosine floor decides how long the page is.** Within a single query the scores *are*
comparable, even though they are not across queries. Measured on the evaluation set, the document a
query is really about is the top semantic hit for 15 of 18 queries and never scores below **0.76** of
the best hit. Keeping documents at **0.70** of the best therefore loses none of them and drops the
long tail: before this, "address proof" returned ten documents including a trust deed and a mortgage
offer; now it returns exactly the four that can evidence an address.

**The lexical floor is relative too**, because `ts_rank_cd` spans three orders of magnitude on this
corpus and has no meaningful absolute scale. Measured across the evaluation queries, genuine
secondary matches score 0.50 to 0.58 of the best hit for that query while incidental ones score
0.03 and below — a tenancy agreement matches "address proof" only because it contains "addresses for
service" and "burden of proof", at `ts_rank_cd` 0.0047 against the real match's 0.833. The floor is
set at **0.05**, inside that gap. This matters more than it looks: reciprocal rank fusion sees a
document's *position* in a list, not how weak it was, so without the floor a 180-times-weaker
lexical match arrives at fusion as a first-place finish.

`SemanticFloorTest` asserts both semantic properties and prints the table.

The principled fix for the absolute floor's overlap is a cross-encoder re-ranker over the fused top
20, which scores query and document *together* and is calibrated in a way a bi-encoder cosine is not.
That is a real model dependency and roughly 50 ms per query, so it is named here rather than built.

---

## Operating notes

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
| `GET /search`, 5 expansion probes, warm | 66 ms median |
| Test suite (60 tests, from clean, no build cache) | 16 s |
| API container resident memory | 760 to 840 MiB |
| Image size | 576 MB |

**Ingest is synchronous.** A `201` means chunked, embedded and committed, so there is no window in
which a caller can read back a document that search cannot find. Embedding happens *before* the
transaction opens, so a connection is never held across model inference. At roughly 35 to 55 ms per
chunk this holds to around 100 chunks per request. The point to move ingest to a background job is
when p99 for `POST /clients/{id}/documents` passes about 5 s, which on these numbers means documents
beyond roughly 100,000 characters, or a sustained bulk import.

**There is deliberately no ANN index.** An exact scan cannot miss a neighbour the way an approximate
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

**The client query uses its index at scale.** On a table of a dozen rows Postgres correctly ignores
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

**Model provenance.** `models/checksums.sha256` is committed with the real hashes;
`./gradlew provisionModel` fetches `model.onnx` and `tokenizer.json` from a pinned Hugging Face
revision and fails the build on a mismatch, so a corrupted or swapped download cannot silently change
every embedding. Tests depend on that task, so a fresh clone runs `./gradlew test` without any manual
step. The model is not committed (90 MB) but *is* baked into the Docker image, so a running container
needs no network beyond Postgres.

Every chunk row records the model that produced it, and startup fails with a "reindex required"
message if the corpus contains vectors from a different model. Vectors from two models share a column
but not a space, and comparing them produces confident nonsense rather than an error.

**Extensions.** `CREATE EXTENSION vector` needs a superuser the first time (`pg_trgm` is trusted).
The compose user is one; managed providers allow both from their extension whitelist.

**Authentication.** Setting `API_KEY` turns on an `X-API-Key` filter on every endpoint except the
health probes and the API documentation. Unset — the compose default — the filter does nothing, so
running the project locally needs no credentials. This is a shared secret for a demo API, not a user
identity system; real multi-tenancy would scope every query by the authenticated advisor's
organisation, which is a `WHERE` clause through `client_id` rather than a new subsystem.

---

## Deploying it

`fly.toml` deploys the same image to Fly.io against any managed Postgres that offers `pgvector` and
`pg_trgm` — the migration creates both on first start.

```bash
fly launch --no-deploy --copy-config
fly secrets set DB_URL=jdbc:postgresql://…  DB_USERNAME=…  DB_PASSWORD=…  API_KEY=…
fly deploy
```

Setting `API_KEY` turns on the `X-API-Key` filter, so the hosted instance needs credentials while
the local one does not. The key is never committed; it is passed as a secret and shared separately.

Two sizing notes learned the hard way and encoded in `fly.toml`: the 256 MB free tier cannot hold
the JVM plus ONNX Runtime's native arenas, so the machine is 2 GB; and the health check needs a
grace period, because the first boot embeds the demo corpus before reporting ready.

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

---

## Decisions, and what I left out

Everything below was considered and deliberately not built. Each line says why, and what adding it
would look like.

- **Generative summaries.** The summary is extractive: the passages closest to the document's own
  centroid (`avg(embedding)` in pgvector), returned in reading order, so every sentence is verbatim
  from the document. It needs no second model and cannot invent a fact about a client's finances.
  A generative version is an isolated swap behind the same endpoint.
- **Semantic search over client descriptions.** "retired educator" will not find a client described
  as a "retired teacher", because clients are matched lexically. Descriptions are one short field and
  the task's client example is lexical, so this is the same chunk-and-embed pipeline pointed at a
  second table — a named extension rather than a gap I missed.
- **A cross-encoder re-ranker.** The principled fix for the calibration overlap above. Real model
  dependency, ~50 ms per query.
- **One ranked list across clients and documents.** The scales are not comparable. This is a
  decision, not an omission — see the two blocks above.
- **An ANN index.** Thresholds measured above.
- **Asynchronous ingest.** Transition criterion named above.
- **Elasticsearch.** A second stateful service to run and keep in sync, for no recall this corpus
  can demonstrate. Postgres already has both retrieval modes.
- **Searching `social_links`.** `array_to_string` is `STABLE`, not `IMMUTABLE`, so it cannot go in
  the generated column. The escape hatch is an `IMMUTABLE` wrapper plus PostgreSQL 17's
  `ALTER TABLE … ALTER COLUMN … SET EXPRESSION AS`.
- **`unaccent`.** The corpus is English; accented names are reachable by trigram similarity. Adding
  it means an `unaccent`-based `IMMUTABLE` wrapper in the generated column.
- **Partial tokens inside document content.** "PLC-88" will not find `PLC-88213`; a trigram index on
  content would fix it at the cost of a second large index.
- **Update and delete, pagination, synonym dictionaries, Prometheus metrics.** Not needed to
  demonstrate search. Metrics in particular are two Micrometer timers through the actuator that is
  already present.

---

## Development

```bash
./gradlew build          # compiles, runs ktlint and the full suite (needs Docker for Testcontainers)
./gradlew ktlintFormat   # apply formatting
./gradlew bootRun        # against a local Postgres; provisions the model first if needed
```

Requires JDK 25 and Docker. Integration tests share one pgvector container through a cached Spring
context; only the API key test, which changes properties, gets a context and container of its own.
Without that sharing the suite would start Postgres once per test class.

Layout: `embedding/` is the tokenizer, ONNX encoder and chunker; `clients/` and `documents/` are the
write path; `search/` holds the three retrievers, the query expander and the fusion; `seed/` loads
the demo corpus through the real service layer.
