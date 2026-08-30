# Search design

The measurements behind [How search works](../README.md#how-search-works).

## Clients: trigrams, not full-text search

Postgres's English parser classifies an email address as a single `email` token, so a full-text
query for a fragment of the domain cannot match inside it and the brief's first example would fail.
`FtsEmailTokenisationProofTest` pins both halves of that:

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

A single `gin_trgm_ops` index serves both, and the planner combines them with a
[`BitmapOr`](operating-notes.md#the-client-query-uses-its-index-at-scale). The score is
`GREATEST(substring ? 1 : 0, word_similarity(...))`, so a literal containment always scores exactly
1.0. Measured on the seeded corpus, this is why the arms are shaped that way:

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

## Documents: three retrievers

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
reduces the corpus to one row per document — its own nearest chunk, which is both that document's
score and its snippet — and shortlists the best 30 of those:

```sql
WITH best AS (
    SELECT document_id, min(embedding <=> CAST(:vector AS vector)) AS distance
    FROM document_chunks GROUP BY document_id
    ORDER BY distance LIMIT 30)
SELECT d.id, …, nearest.content AS snippet, 1 - best.distance AS score
FROM best JOIN documents d ON d.id = best.document_id
JOIN LATERAL (SELECT content FROM document_chunks WHERE document_id = best.document_id
              ORDER BY embedding <=> CAST(:vector AS vector), id LIMIT 1) nearest ON true
ORDER BY best.distance, d.id;
```

**The shortlist is counted in documents, and that is the whole point of the reduction.** Taking the
50 nearest chunks and collapsing them to one per document afterwards reads as equivalent and is not.
The seeded reports run to 22 chunks each, so two or three of them fill a chunk-shaped window, and
what does not fit is not ranked low — it is not ranked at all. The documents most easily squeezed
out are the short ones, and the electricity bill this page is largely about is three chunks long.
The failure is invisible from the outside, which is the argument for not writing it that way:
nothing distinguishes "no good match" from "never looked". All three arms are therefore cut to the
same depth in the same unit, since fusion combines them as rankings — the semantic arm applies the
number once per expansion probe and then keeps each document's best score, so an expanded query
carries more than 30 into the floors. `DocumentSearchRepositoryTest` pins the semantics for both
learned arms.

The formulation matters as much as the semantics, and it is not obvious which way. Three ways to
write "one row per document", timed with `EXPLAIN ANALYZE`, median of five warm runs on the seeded
corpus and on the same corpus grown to the large scale of [load and limits](load-and-limits.md):

| Formulation | 153 chunks | 99,700 chunks |
| --- | --- | --- |
| Nearest 50 chunks, collapsed afterwards — bounded by chunks, so it starves | 0.87 ms | 100 ms |
| `DISTINCT ON (document_id)` over the whole table | 0.73 ms | 424 ms |
| `min()` per document, text fetched for the survivors — shipped | 0.85 ms | 244 ms |
| The same `min()` per document over the sparse column with `<#>` | 1.2–2.6 ms | ~420 ms |

At the corpus that ships, all four are the same query. At the large corpus `DISTINCT ON` is the one
that is genuinely more expensive: it sorts every chunk in the corpus, where `min()` groups them
through a hash table and only the thirty survivors ever pay for their text. (A `CROSS JOIN LATERAL`
per document — the shape that reads most naturally — was measured too, and is the worst of the four
at 1.1 s: a nested loop over every document, each with its own index scan and sort.)

The remaining gap to the chunk-bounded query is not extra work. With
`max_parallel_workers_per_gather = 0` the shipped query runs in 182 ms and the chunk-bounded one in
176 ms — the difference is that a top-N heap hands itself to two parallel workers and a hash
aggregate does not. Every chunk is scored either way: the scan is exact, not approximate, and what
changes is only what is ranked afterwards.

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

### Sparse: learned term weights

The third arm is SPLADE-style *learned sparse* retrieval. A masked-language-model encoder reads each
chunk and, instead of a 384-dimensional point, emits a weight for every one of the 30,522 WordPiece
vocabulary terms the chunk states or implies — most of them zero. Per term `j`:

```
w_j = log(1 + max(0, max over unmasked positions i of logit_ij))      then the five special-token ids are zeroed
```

The maximum over positions and the `log(1 + relu(·))` are the published pooling; because both
functions are monotone, the code takes the maximum of the raw logits first and applies the transform
once per term — 30,522 transcendental calls per chunk instead of sequence × 30,522, and the same
vector to the last bit. The result is stored beside the dense vector as a pgvector `sparsevec(30522)`,
8 bytes per non-zero plus 16; on the seeded corpus a chunk activates 145 to 666 terms (median 278),
so a sparse vector is about 2.3 kB against the dense vector's 1.5 kB.

The checkpoint is `opensearch-neural-sparse-encoding-doc-v2-mini`, an *inference-free* model: only
documents go through the encoder. A query is its distinct wordpieces, each weighted from the
checkpoint's frozen IDF table (`the` 0.135, `address` 4.89, `electricity` 6.12), and the score is the
plain inner product — `q · w`, unnormalised, because that is what the model was trained against.
So searching costs a table lookup and one more exact scan; no second forward pass. Both models
share the bert-base-uncased vocabulary entry for entry, which is what lets one tokenizer and one
chunker serve both — `TokenizerParityTest` proves it on text and startup re-checks it on the files.

The SQL is the semantic arm's with `<#>`, pgvector's *negative* inner product, so `min()` is the best
chunk and its negation the score:

```sql
WITH best AS (
    SELECT document_id, min(sparse_embedding <#> CAST(:vector AS sparsevec)) AS distance
    FROM document_chunks GROUP BY document_id
    HAVING min(sparse_embedding <#> CAST(:vector AS sparsevec)) < 0
    ORDER BY distance, document_id LIMIT 30)
SELECT d.id, …, nearest.content AS snippet, -best.distance AS score …
```

The `HAVING` matters: a document sharing no term with the query sits at exactly zero and would
otherwise fill the shortlist in id order, so an empty sparse result means "nothing shares a term
with this", not "thirty ties". Before any floor sees it, the score is divided by the query's own mass
(the sum of its IDF weights), which turns it into the average learned weight the best chunk gives the
query's terms and puts a two-word and a six-word query on one scale.

**Why this checkpoint.** Every `naver/*` SPLADE checkpoint is CC-BY-NC-SA and two are gated, which
rules the whole family out for a public project, so two Apache-2.0 candidates were measured on the
seeded corpus with `SparseModelExperiment` (opt-in, like the dense one), each ranking all 20
documents for the golden queries with the sparse arm alone:

| Checkpoint | hit@5 / MRR alone | nonsense best vs weakest true positive | ms per chunk | query side | size |
| --- | --- | --- | --- | --- | --- |
| `opensearch-neural-sparse-encoding-doc-v2-mini` | 17/18, 0.802 | **0.129 vs 0.179** — a clean gap | 22 | IDF lookup, 0 ms | 138 MB |
| `Splade_PP_en_v1` (symmetric BERT-base) | 17/18, 0.865 | **0.343 vs 0.208** — overlap | 89 | MLM pass, 4–9 ms per probe | 532 MB |

The symmetric model scores higher alone because it expands queries as well as documents — and that
is exactly what lets nonsense through: "zzzqqq nonsense token" expands onto terms the corpus has,
and no absolute floor separates it from real answers without cutting them. The inference-free model
keeps the gap this whole design relies on ("a query with no plausible answer returns nothing"), at a
quarter of the ingest cost and a quarter of the size. Its ONNX export comes from a third-party
mirror whose weights, tokenizer and IDF table are byte-identical to the official repository's;
`SparseEncoderTest` pins the encoder's output for one sentence to the values the official PyTorch
checkpoint produces, to three decimals, so the export is trusted because it was checked.

**What the arm adds, and what it does not.** Full-text search ANDs the query's terms, so
"electricity supplier statement" finds nothing — `supplier` is absent. The sparse arm scores the two
terms that are there and ranks the bill first. "double taxation treaty" reaches a chunk that only
says "double taxation *agreement*", because the encoder put `treaty` (0.35) and `agreement` (0.66)
into that chunk's expansion. The lexical arm stays authoritative on reference codes: WordPiece splits
`PLC-88213` into `plc`, `-`, `88`, `##21`, `##3`, and the policy schedule wins on those pieces, but a
trust deed that merely says "plc," scores a third as much, which is what the relative floor is for.
And it does not fix the brief's example: the bill's chunks carry `address` at 0.53 and `utility` at
0.28, but nothing the query "address proof" can connect to — the arm ranks the bill fourth, one place
better than the dense model, still behind the onboarding checklist. The lexicon stays.

Running the lexicon's probes through this arm as well was measured and rejected: on the golden set
as it then stood no rank changed, and a two-word probe like "bank statement" is a strong partial match
for most of the corpus, so "address proof" carried fifteen sparse candidates into fusion instead of
three and the page grew from four documents to ten. With the paraphrase queries added later the same
column gains 0.02 of MRR (0.860 against 0.841) — recorded in the [fusion table](#fusion), and still
not worth the pages; the ablation keeps printing it. Expansion widens the semantic arm only.

## Why the task's own example needs more than a model

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
single case in the same way. (`ModelSelectionExperiment` reproduces this; see its header.) The
learned sparse model added later does no better in kind: it ranks the bill fourth for "address proof",
on the strength of the word "address" in its own text, and knows nothing of "proof".

That is not a model quality problem. "An electricity bill can evidence where you live" is
**procedural knowledge about a domain**, not a distributional fact about English, and a model
trained on general text has no way to know it. Reaching for a larger model would have burned the
budget and changed nothing.

So the knowledge is stated explicitly, in `src/main/resources/search/query-expansions.json`: five
concepts, each with the ways an advisor phrases the requirement (its *paraphrases*) and the phrases
to also search for — where each comes from, and what each probe reaches, is in [Where the lexicon
comes from](#where-the-lexicon-comes-from) below. A query that reaches a concept is run as several probes and each document keeps
its best score across them. Taking the maximum rather than blending the probes into one vector
matters — averaging "address proof" with "utility bill" produces a vector that is a weaker match for
both than either is alone.

**Matching a query to a concept.** The lexicon says *what* to search for; a model decides *when* it
applies. The first version decided by substring: a query expanded only if it literally contained one
of a concept's phrases, so "something official with her home address on it" never reached the address
rule at all, and 2 of the 21 golden document queries expanded. Now each rule's phrasings and its
concept name are embedded once at startup with the dense model, together with its expansions (49
short texts, 101 ms in the container). A query is embedded once, inside the expander, and that vector is also what
the semantic arm scans with; a rule's score is the cosine to its nearest phrasing, and a rule fires
when that score clears an absolute floor and stays within a ratio of the best rule's:

```
floor   = max(search.concept-floor, best × search.concept-floor-ratio)
matched = rules whose nearest phrasing scores ≥ floor          # strongest first; expansions interleaved as before
```

The absolute floor rejects a query about nothing in the lexicon; the relative one stops a
single-concept query dragging its embedding-space sibling in ("proof of address" scores the identity
rule at 0.43) while a query naming two concepts keeps both. The numbers, from `ConceptFloorTest`:

| Measurement | Value |
| --- | --- |
| Closest query about nothing in the lexicon ("retirement income planning" → the income rule) | 0.470 |
| Weakest paraphrase that fires ("someone to run his finances if he becomes unable to") | 0.508 |
| A phrasing inside a longer sentence, weakest ("I need proof of address for Jane Roe") | 0.533 |
| Strongest sibling, as a share of an exact phrasing's own score ("address verification" → identity) | 0.64 |
| Weakest second concept of a two-concept query, as a share of the first | 0.755 |
| A two-concept query whose second concept measures under the ratio and is listed ("power of attorney and proof of identity for the attorneys" → identity) | 0.74 |

So `concept-floor` is **0.50** and `concept-floor-ratio` **0.75**, each 0.03 clear of the row that
binds it. Both are in the dense model's cosine units and move with `embedding.model-id`.

Two things this design does not do, stated rather than tuned around. It does not reach every
paraphrase: intent is a thinner signal than topic in this embedding space, and "what ID do we need from
him" (0.47), "what can he send to show where he lives" (0.44) and "is the LPA registered with the
Office of the Public Guardian" (0.35 — an acronym no phrasing carries) sit under the floor, listed in
the test so the limit stays visible; the lever is a phrasing in the lexicon, never a lower floor — and
not every phrasing is a lever: "photo ID" was tried for the first of those, left it at 0.47, and became
the nearest phrasing for reference codes and nonsense queries, so it went. And it does not tell a
question about a fact from a request for its evidence: "what is the client's current address" reaches
the address rule at 0.65, on purpose — the documents that evidence an address are the documents that
state it. Two queries pull a sibling in within the ratio: "confirm the client is who he says he is"
fires identity first and then address and income; "evidence of where the invested money came from"
fires source of funds first and then income at 0.76 of it — a salary credit is a source of funds, so
both rides are allowed and listed; interleaving keeps the intended concept's expansion first, and the
semantic floors still apply to what the extra probes find. One two-concept query goes the other way:
"power of attorney and proof of identity for the attorneys" scores the capacity rule at 0.81 and
identity at 0.74 of that, so identity stays silent — listed, not tuned. Phrasings that lean on the word
"client" attract every client query — an earlier phrasing, "acting on behalf of a client", was the
strongest false match for six unrelated queries and was dropped for that reason; the JSON's comment
says so.

Expansion widens the **semantic arm only**. The lexical arm's value is precision on exact tokens,
and OR-ing extra phrases into it would trade that away for recall the semantic arm already provides;
the sparse arm was measured with the probes and without, and on the golden set of the time they
changed no rank — only how long the page was ([above](#sparse-learned-term-weights)).

It is not free, but it is cheaper than it was: the expansions' vectors are embedded at startup, so an
expanded query pays one forward pass for its own vector — as every query does — and the cost is each
probe's own vector scan: 45 ms against 20 ms for a plain one, from 68 against 30 when the probes were
embedded per query, and 30–33 ms for the three-probe rules (income, capacity, source of funds). That
is why the expander caps a query at five probes and why the lexicon's order is a budget. More queries
now pay it — 13 of the 35 golden document queries reach a concept (3 of the original 21, from 2 by
substring) — which is the trade a paraphrase-aware matcher makes, and the search log names the concept
and its score on every request so a fire is a grep away.

The limits are worth stating: the normaliser widens how a concept can be asked for, not what the
lexicon knows, and the lexicon is only as good as the person editing it. Its floors were measured on
five concepts and some ninety queries; a sixth concept near an existing one has to be checked against
the sibling table before it ships — the source-of-funds rule was, and one of its paraphrases rides
income in at 0.76. In a real product the knowledge itself would come from a maintained
document taxonomy, or from classifying documents by evidence type at ingest. What it should *not* come
from is hoping a bigger model has it.

### Where the lexicon comes from

The reader this is for works at a financial-services firm and will type their own queries, so the
list has to be defensible with a source, not with "I chose these". The frame is the adviser's own
regulation rather than a vendor's document list: the seed corpus is a UK advisory practice (a
suitability report, a pension consolidation, an EIS statement, a trust deed, a registered LPA, a CRS
self-certification), and each rule is grounded in the text that makes its requirement a requirement.
Every text was read in its current form before it was cited — the JMLSG Guidance Part I (June 2023,
revised August 2025) for customer due diligence; the FCA Handbook (COBS 9A.2, COBS 19.1, MCOB 11.6);
the Mental Capacity Act 2005 and the Powers of Attorney Act 1971; HMRC's AEOI manual (IEIM402015,
IEIM403140) — and quoted in short fragments with the paragraph number. Onfido's public API
specification was read as one vendor's concrete instance of an address list (18 of its 78 document
types are accepted as proof of address) and as evidence that such lists vary between firms, not as an
authority.

**What each rule is and reaches.** `QueryExpanderTest` prints, for every expansion, the documents it
reaches above the semantic floor that the bare concept does not, and the semantic page every expanding
golden query produces with the probe that carried each document. The table is read from that run.
Order is a budget — a query naming one concept runs its first four expansions, a query naming two runs
two of each — so a probe that reaches nothing seeded goes last.

| Rule (phrasings) | Source | Expansions, in order | What each reaches (cosine) |
| --- | --- | --- | --- |
| evidence of address (7) | JMLSG 5.3.75 — "Current council tax demand letter, or statement", a document from "a regulated utility company"; 5.3.76 — "current bank statements, or credit/debit card statements, issued by a regulated financial sector firm in the UK, or utility bills" | utility bill; bank statement; council tax bill | the electricity bill 0.48 (13th for the bare query); the bank statement 0.63 — title-shaped, so it sets the page's floor at 0.44; nothing: the checklist 0.44 and the capital gains computation 0.43 sit under that floor, and no council tax document is seeded |
| evidence of identity (8) | 5.3.75 — "Valid passport", "Valid photocard driving licence (full or provisional)", "National Identity card"; 5.3.90 — "requiring copy documents to be certified by an appropriate person" | passport; driving licence; national identity card; certified copy of identity document | the onboarding checklist 0.40, 0.45, 0.51, 0.56 — the same document each time — and through the last two the CRS form 0.44, whose TINs were "verified against his Polish identity card" |
| evidence of income (6) | MCOB 11.6.13G(1) — "Examples of evidence of income … are payslips and bank statements"; 11.6.9G(2) — "checking the level of income regularly paid into a bank account" | salary credit on a bank statement; payslip | the bank statement 0.47, whose only income line is a `SALARY` BACS credit and which the bare query scores 0.31; nothing: the fee agreement 0.31 stays under the floor |
| acting for someone who has lost capacity (6) | MCA 2005 s.9 — authority only once "registered in accordance with Schedule 1"; s.16(2)(b) — a court-appointed "deputy" | lasting power of attorney attorney appointed; deputy appointed by the court | the LPA 0.67 (rank 1 for the bare query too); nothing: the LPA again at 0.31, and no deputyship order is seeded |
| source of funds and source of wealth (5) | JMLSG 5.5.32 — "a copy of the relevant will" for inheritance, "evidence of conveyancing" for a property sale; 5.5.6 — "inheritance, divorce settlement, property sale" | proceeds from the sale of a property; inheritance under a will | the capital gains computation 0.54 (bare 0.17 — it itemises the sale proceeds and "Conveyancing fees and disbursements" and never says source of funds), with the buy-to-let review 0.48; the estate-planning notes 0.51 ("Gerald left his entire estate to her"), with the trust deed 0.51 and the policy schedule 0.44 |

Two rules re-find what the bare query already ranks first. Every identity probe reaches the
onboarding checklist, which the bare queries rank first or second on their own: the corpus holds no
identity *document*, only the firm's record of the checks, and that record uses the requirement's own
words. Both capacity goldens were rank 1 before any expansion, and every embedding model measured
ranked the LPA first. The rules stay for the corpus a reviewer may post: a passport scan (a
machine-readable zone, "HM Passport Office") or a Court of Protection order is no closer to "proof of
identity" or "who can act for him" than the electricity bill is to "address proof".

**Named by the texts, measured out.** The texts name more documents than ship, and each was measured
before it went. `tax return` (MCOB 11.6.9G(5)) put five documents that merely mention self-assessment
on the income page — the trust deed 0.41, the capital gains computation 0.40, the EIS statement 0.40,
the annual review 0.39, the suitability report 0.35. Every wording of a pension statement (11.6.15G(2)
— "pension statement", "pension benefit statement", "statement of pension benefits", "annual benefit
statement from a pension scheme") scored *Suitability Report: Pension Consolidation* between 0.59 and
0.68 and the *Annual Benefit Statement* it was meant to reach between 0.46 and 0.52: the probe would
have handed the income page to a document about consolidating pensions and, at 0.68, set a floor of
0.47 that cuts the benefit statement itself. `P60 end of year certificate` made the CRS
*self-certification* the top hit for "proof of income" (0.43, on "certificate") and reached no P60;
`employment contract salary` reached nothing above 0.33. `mortgage statement` is on the seeded firm's
own accepted list and not in JMLSG 5.3.75–5.3.76 — the slot where the firm's list diverges from the
regulator's; it reaches the mortgage offer at 0.52, and it cost "address proof" and "proof of
address" a rank (4 to 5) for a document the sparse arm already places second on the surname. `tenancy
agreement` is named by neither: its one reach, the tenancy summary at 0.53, states the *tenants'*
address — the client is the landlord, care of the letting agent — and it sat second on every address
page above the bill; without it "something official with her home address on it" moved from third to
second. `donor best interests decisions` (MCA s.4 is a duty, not an artefact) reached the LPA at
0.50 and otherwise the estate notes and the trust deed — nothing the first probe lacks. The rename
`deputy appointed by the Court of Protection` was measured and reversed: the tenancy summary (0.314)
edged the LPA (0.313). `council tax demand` and `photocard driving licence`, the texts' own surface
forms, reach the same documents as the shipped wordings (the demand puts the capital gains computation
first, 0.43) and were not swapped in for an unmeasured vector; the JSON's comment records the forms.
`HMRC correspondence` and `benefit entitlement letter` as address probes reach the checklist 0.42 and
the trust deed 0.40, and the trust deed, the EMI summary and the policy schedule at 0.40 — no HMRC
letter is seeded, and the benefit statement they would pull carries no address.

**The removed rule.** "life cover paid on death" shipped in the first lexicon and is gone. No text
read makes life cover an evidentiary requirement — COBS 9.2.1R's demands-and-needs test is advice
content — and the rule encoded what a whole-of-life policy pays rather than which document evidences
what. Its golden, "payout when the policyholder dies", was rank 1 before the normaliser and is rank 1
without the rule; "death benefit" alone puts the policy schedule first at 0.55 with the pension
statement's survivor benefits second at 0.39. With the rule, `policy written in trust beneficiaries`
scored 0.67 and set a floor of 0.47 that pulled the trust deed (0.62) and the estate-planning notes
(0.57) onto the page while cutting that pension statement (0.42). Its five phrasings now sit in
`ConceptFloorTest`'s unrelated list and fire nothing; the nearest lands at 0.42 against the capacity
rule.

**Considered and rejected.** A tax-residency rule (HMRC IEIM403140 — the self-certification states
the jurisdictions of tax residence and a TIN for each): "which countries is he tax resident in" is
rank 1 with no rule, every candidate expansion ("self-certification", "jurisdiction of tax residence",
"taxpayer identification number") is verbatim in the form so the lexical and sparse arms already carry
it, and the address rule's "residency proof" is the nearest phrasing at 0.34. (The form declares both
jurisdictions, which is what IEIM402015 requires from 1 January 2026 — self-certifiers "may not rely on
tiebreaker rules contained in tax conventions" — while the adviser's note applies the Article 4
tie-breaker, a tax-computation point rather than a form point.) A pension-transfer rule (COBS 19.1):
"retirement income planning" and "drawdown sustainability" are rank 1 with no rule, and the scope fact
matters more than the rule — the Glossary's "pension transfer" is a transfer "in respect of any
safeguarded benefits", so the seeded *Suitability Report* (three defined-contribution pots) is COBS
9.2 business with 19.1.1AR(2)'s guaranteed-annuity-rate carve-out, and the *Annual Benefit Statement*
("safeguarded benefits worth more than £30,000") is the COBS 19.1 document. "CETV" scores 0.24 against
every phrasing and the lexical arm has no abbreviation table: a stated limit. A suitability-evidence
rule (COBS 9A.2.1R's knowledge and experience, financial situation, objectives): categories with no
document attached, present in no seed document; "inconsistent answers on the risk questionnaire"
(9A.2.9R(2)(d), "obvious inaccuracies") is rank 1 unaided. An interest-only repayment-strategy rule
(MCOB 11.6.41R(1)(a) — "evidence that the customer will have in place a clearly understood and
credible repayment strategy"): the offer records it in those words and is rank 1 unaided. Acronyms and
umbrella vocabulary as phrasings: "KYC documents on file" 0.36, "AML checks on the new client" 0.41
(towards income), "ID&V for the joint applicant" 0.49, "CDD" 0.30 and "PoA" 0.26 fire nothing, while
the spelled-out forms do — "customer due diligence identity documents" 0.68 (identity), "verify the
customer's residential address" 0.72 (address); and "PoA" is genuinely ambiguous in this firm's own
records, where the checklist files proof of address under `POA-01` and the LPA is a power of attorney.
Each of these is a row in `ConceptFloorTest` and, where a document answers it, a golden query that
pins that no rule is needed.

**The limit the lexicon cannot cross.** "proof of income" puts the bank statement first in the
semantic arm — from 13th before the income rule was re-pointed — and sixth in the fused result. Five
documents contain both "proof" and "income" for the keyword and sparse arms, and reciprocal rank
fusion with k = 60 ranks any document two arms find above one that a single arm finds, whatever its
position (2/65 against 1/61). Phrased "evidence of earnings" the statement is third and "what shows
how much he earns" second: those are the goldens, and the sixth place is printed by `QueryExpanderTest`
every run so it stays visible.

**Why this is configuration and not something a model could learn.** The texts set a standard and
leave the list to the firm. JMLSG 5.3.112 says the guidance "does not require that in all cases a
customer's address should be verified"; Part I sets no numeric recency for an identity or address
document — only "current" and "recent" — where the seeded onboarding checklist wants one "dated within
the last three months"; that checklist refuses the mobile phone bill Onfido's proof-of-address list
accepts and takes a national identity card "issued by an EEA state" only, a restriction 5.3.77 allows;
MCOB 11.6.8R accepts income evidence "whether document-based or derived through the use of automated
systems", and 11.6.50R(2) pushes "the evidential requirements and other controls" for repayment
strategies into the lender's own policy. Two firms reading the same text produce two lists; this
file holds one firm's, and the reach table above is what that firm's list does on this corpus. What
the seeded corpus lacks is worth naming, because the trailing probes exist for it: a payslip, a P60, a
tax return, a will, a grant of probate, a deputyship order, the council tax demand the checklist
records receiving, an HMRC letter, an identity document.

## Fusion

The three document rankings are combined with reciprocal rank fusion — `1/(k + rank)`, `k = 60` —
from Cormack, Clarke and Buettcher (SIGIR 2009), and the default hybrid combiner in Elasticsearch,
OpenSearch and Azure AI Search. It combines *rankings* rather than scores, which is the point:
`ts_rank_cd`, an inner product and a cosine are on unrelated scales, and no fixed weighting between
them survives a change of corpus. A document several retrievers rank outscores one that a single
retriever ranks highly, so agreement between kinds of evidence wins. It is 20 lines of Kotlin with
unit tests that check the arithmetic by hand (`1/61`; `1/65 + 1/68`; `1/65 + 1/68 + 1/70`).

A third list changes what agreement is worth, and the change is pinned rather than hoped past: two
tenth places (`2/70`) now beat a first place in one list (`1/61`). Since the lexical and the sparse
arm both reward literal tokens, that is the trade — a document two arms half-like can outrank one
the semantic arm alone is sure of. `SearchArmAblationTest` measures it on the golden document queries
by fusing every combination of the floored arms:

| Arms | hit@5 | MRR |
| --- | --- | --- |
| keyword | 17/35 | 0.486 |
| sparse | 26/35 | 0.703 |
| semantic | 33/35 | 0.829 |
| keyword + semantic — the two-arm system | 34/35 | 0.812 |
| keyword + sparse | 26/35 | 0.702 |
| **keyword + sparse + semantic** | **35/35** | **0.802** |
| … with the lexicon's probes through the sparse arm too | 34/35 | 0.857 |

Read the hit column before the MRR column. Three arms is the only configuration that finds every
golden document in the top five; the semantic arm alone misses two and the two-arm system one. The
MRR ordering is the other way round because of what the nine queries added with the lexicon review
are: documents that only the lexicon-widened semantic arm reaches (the bank statement for "evidence
of earnings", the capital gains computation for "evidence of where the invested money came from"),
and for those every extra arm can only add competitors — a document two arms find outranks one a
single arm finds, whatever its position ([the fusion limit](#where-the-lexicon-comes-from)). On the 26
queries before that review the same table read 0.821 for two arms and 0.841 for three, and the
probes-through-sparse row 0.860; that row now loses a query ("proof of address for Mr Lindqvist"
drops to ninth when the address probes run through the sparse arm as well), which is one more reason
it stays unshipped. The ablation applies the product's own tie-break — agreement, then the most
literal arm, then title — so its three-arm column is the evaluation table's.

(On the 21 queries that predate the paraphrases: two arms 0.810, three arms 0.859, probes through the
sparse arm 0.859 — the probes column only started to pay once paraphrase queries expanded, and it
still costs the page length described under [Sparse](#sparse-learned-term-weights).)

No query the two arms hit is lost, and the mean reciprocal rank rises because agreement is now
three-way: "who can act for a client if they lose capacity" and "inheritance tax on gifts" move from
second or third to first when the sparse list confirms the semantic one.

Two consequences that shaped the code:

- **Relevance cut-offs must be applied before fusion.** Once a score has become a rank, "not similar
  enough" is no longer expressible, so every arm is filtered while its numbers still exist. See
  [Calibrating the cut-offs](#calibrating-the-cut-offs).
- **Clients are not in the fusion.** A client can never appear in a document ranking, so an exact
  email match would be permanently capped at `1/61` and would lose to an average document that
  happened to appear in both lists. Hence the [two blocks](../README.md#api).

Exact ties are common — two documents that place equally in different lists score identically — and
are broken by agreement first, then by the most literal evidence (a lexical hit is a fact, the token
is in the document; a sparse hit is the token or a learned expansion of it in a chunk; a semantic hit
is an estimate), then by title, and only then by the primary key, which is there to make the order
stable rather than to mean anything. Ranking *on* the id was the bug: deployments do not share ids, so results reordered
between one install and the next. It was caught by running the evaluation twice.

## Evaluation

`golden-queries.json` holds 42 queries an advisor might type, each with the one result that must come
back. `SearchQualityTest` asserts every one lands in the top five and prints mean reciprocal rank, so
a change that keeps every query passing while pushing results down the list is still visible. Three
document queries were added with the sparse arm, each a partial-term query the lexical AND cannot
answer ("electricity supplier statement" — `supplier` is absent); on the original eighteen the
two-arm system scored 0.778 and the three-arm one 0.835. Five more came with the semantic normaliser:
two paraphrases of the address requirement that no phrase in the lexicon appears in — unreachable
before it — and one paraphrase per other concept that already passed on meaning alone, pinned so a
concept firing on it never costs the rank. Nine more came with the review of the lexicon against its
sources ([above](#where-the-lexicon-comes-from)): two the income rule earns ("evidence of earnings"
and "what shows how much he earns" — the bank statement says neither word), one the source-of-funds
rule earns ("evidence of where the invested money came from" — the capital gains computation), and
six pins: requirements the texts name for which no rule exists, and a rejected probe's document,
placed where meaning and the sparse arm already put them. The hard case is stated as measured: for
"documents that show where the client lives" the normaliser reaches the address rule at 0.81 and the
bank statement, which carries the address, ranks second; the electricity bill is fourth, behind the
statement and two partial sparse matches.

| Set | Queries | hit@5 | MRR |
| --- | --- | --- | --- |
| Documents | 35 | 35/35 | 0.802 (0.847 on the 26 before the lexicon review, 0.859 on the original 21) |
| Clients | 7 | 7/7 | 0.929 |

The single client query not at rank 1 is "retired teacher", which ties a retired *teacher* with a
retired *educator*. Both are returned; the tie is genuine.

This is a small hand-built set, and that is its honest limitation: it protects against regressions in
the behaviour I chose, not against being wrong about what advisors actually search for.

## Calibrating the cut-offs

Every retriever gets a relevance floor before fusion, and none is a single absolute number, because
none of the scores has a calibrated scale across queries. All of the thresholds were measured on
this corpus rather than picked.

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
offer; now it returns the two that state one — the bank statement and the bill — and, for "proof of
address", the checklist that lists what is accepted.

**The lexical floor is relative too**, because `ts_rank_cd` spans three orders of magnitude on this
corpus and has no meaningful absolute scale. Measured across the evaluation queries, genuine
secondary matches score 0.50 to 0.58 of the best hit for that query while incidental ones score
0.03 and below — a tenancy agreement matches "address proof" only because it contains "addresses for
service" and "burden of proof", at `ts_rank_cd` 0.0047 against the real match's 0.833. The floor is
set at **0.05**, inside that gap. This matters more than it looks: reciprocal rank fusion sees a
document's *position* in a list, not how weak it was, so without the floor a 180-times-weaker
lexical match arrives at fusion as a first-place finish.

`SemanticFloorTest` asserts both semantic properties and prints the table.

**The sparse floors are on the inner product divided by the query's mass** — an unbounded score in
the model's own units has no absolute floor until it is put on a per-query scale, and the average
learned weight a chunk gives the query's terms is one. Measured with `SparseFloorTest`:

| Measurement | Value |
| --- | --- |
| Nonsense queries, best document | 0.129, 0.093, 0.086 |
| Weakest genuine answer ("energy performance certificate" → the buy-to-let review) | 0.179 |
| Incidental one-term overlap ("plc," in the trust deed, against `PLC-88213`) | 0.32 of the best |
| Genuine secondary matches, as a share of the best | 0.53 and up |

So the absolute floor is **0.15**, between the nonsense peak and the weakest real answer, and the
relative floor is **0.45**, between an incidental overlap and a genuine secondary match. Both move if
the checkpoint does, which is why `sparse.model-id` is recorded on every chunk row.

**The concept floors are cosines between a query and a lexicon rule's phrasings**, in the dense
model's units, and were read off the tables in [Matching a query to a
concept](#why-the-tasks-own-example-needs-more-than-a-model): the closest unrelated query scores
0.470, the weakest paraphrase that must fire 0.508, so the absolute floor is **0.50**; a two-concept
query's second concept scores at least 0.755 of its first, an exact phrasing's strongest sibling at
most 0.64, so the relative floor is **0.75**. `ConceptFloorTest` asserts both from both sides and
prints the table, including the paraphrases it leaves under the floor.

The principled fix for the absolute floor's overlap is a cross-encoder re-ranker over the fused top
20, which scores query and document *together* and is calibrated in a way a bi-encoder cosine is not.
That is a real model dependency and roughly 50 ms per query, so it is named here rather than built.

## Scope: what search deliberately does not do

- **Generative summaries.** The summary is extractive: the passages closest to the document's own
  centroid (`avg(embedding)` in pgvector), returned in reading order, so every sentence is verbatim
  from the document. It needs no second model and cannot invent a fact about a client's finances.
  A generative version is an isolated swap behind the same endpoint.
- **Semantic search over client descriptions.** "retired educator" will not find a client described
  as a "retired teacher", because clients are matched lexically. Descriptions are one short field
  and the brief's client example is lexical, so this is the same chunk-and-embed pipeline pointed at
  a second table — a named extension rather than a gap.
- **One ranked list across clients and documents.** The scales are not comparable: a client score is
  a trigram similarity in 0..1, a document score a fusion weight around 0.016. Hence the two blocks
  in the [response](../README.md#api) — a decision, not an omission.
- **Searching `social_links`.** `array_to_string` is `STABLE`, not `IMMUTABLE`, so it cannot go in
  the generated column. The escape hatch is an `IMMUTABLE` wrapper plus
  `ALTER TABLE … ALTER COLUMN … SET EXPRESSION AS` (PostgreSQL 17+).
- **`unaccent`.** The corpus is English; accented names are reachable by trigram similarity. Adding
  it means an `unaccent`-based `IMMUTABLE` wrapper in the generated column.
- **Partial tokens inside document content.** "PLC-88" will not find `PLC-88213`; a trigram index on
  content would fix it at the cost of a second large index.
