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

So the knowledge is stated explicitly, in `src/main/resources/search/query-expansions.json`: eleven
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
concept name are embedded once at startup with the dense model, together with its expansions (144
short texts, 0.3–0.6 s across two container starts). A query is embedded once, inside the expander, and that vector is also what
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
| Weakest paraphrase that fires ("something official with her home address on it") | 0.509 |
| A phrasing inside a longer sentence, weakest ("I need proof of address for Jane Roe") | 0.533 |
| Strongest sibling, as a share of an exact phrasing's own score (a liabilities phrasing → expenditure) | 0.73 |
| Weakest second concept of a two-concept query, as a share of the first | 0.755 |
| A two-concept query whose second concept measures under the ratio and is listed ("power of attorney and proof of identity for the attorneys" → identity) | 0.74 |
| A paraphrase another rule outranks, listed ("someone to run his finances if he becomes unable to": expenditure 0.513, authority to act 0.508) | 0.99 |

So `concept-floor` is **0.50** and `concept-floor-ratio` **0.75**, 0.03 and 0.02 clear of the rows
that bind them. Both are in the dense model's cosine units and move with `embedding.model-id`.

Two things this design does not do, stated rather than tuned around. It does not reach every
paraphrase: intent is a thinner signal than topic in this embedding space, and "what ID do we need from
him" (0.47), "what can he send to show where he lives" (0.44) and "is the LPA registered with the
Office of the Public Guardian" (0.35 — an acronym no phrasing carries) sit under the floor, listed in
the test so the limit stays visible; the lever is a phrasing in the lexicon, never a lower floor — and
not every phrasing is a lever: "photo ID" was tried for the first of those, left it at 0.47, and became
the nearest phrasing for reference codes and nonsense queries, so it went. And it does not tell a
question about a fact from a request for its evidence: "what is the client's current address" reaches
the address rule at 0.65, on purpose — the documents that evidence an address are the documents that
state it. Some queries pull a sibling in within the ratio: "confirm the client is who he says he is"
fires identity first and then address and income; "evidence of where the invested money came from"
fires source of funds first and then assets and income — a salary credit and an investment are both
where money came from — so those rides are allowed and listed; interleaving keeps the intended
concept's expansion first, and the semantic floors still apply to what the extra probes find. Inside
the financial-situation cluster (income, assets, liabilities, expenditure, repayment strategy, property
ownership) riders are allowed by declaration rather than one row at a time, because they are the
cluster's nature — [the sibling matrix](#where-the-lexicon-comes-from) below. Two rows go the other
way and are listed as such: "power of attorney and proof of identity for the attorneys" scores the
authority rule at 0.81 and identity at 0.74 of that, so identity stays silent; "someone to run his
finances if he becomes unable to" is won by expenditure (0.513, via "regular financial commitments")
over the authority rule (0.508), which still fires second. Phrasings that lean on a word every query
about a person carries attract every such query: "acting on behalf of a client" was the strongest false
match for six unrelated queries; six new phrasings written with "the client" ("what the client owes")
put liabilities and dependants at 0.77 of each other; pronouns did the same ("valuation of her
investments" made assets win a source-of-funds question about "the money she is investing"). Each was
reworded without the word and re-measured; the JSON's comment says so.

Expansion widens the **semantic arm only**. The lexical arm's value is precision on exact tokens,
and OR-ing extra phrases into it would trade that away for recall the semantic arm already provides;
the sparse arm was measured with the probes and without, and on the golden set of the time they
changed no rank — only how long the page was ([above](#sparse-learned-term-weights)).

It is not free, but it is cheaper than it was: the expansions' vectors are embedded at startup, so an
expanded query pays one forward pass for its own vector — as every query does — and the cost is each
probe's own vector scan: 43–46 ms against 19–21 ms for a plain one on the container, from 68 against
30 when the probes were embedded per query. Every rule now carries at least four expansions, so every
expanding query runs the full five probes, whether one concept fires or three. That is why the
expander caps a query at five probes and why the lexicon's order is a budget. More queries now pay it —
13 of the 34 golden document queries reach a concept (3 of the original 21, from 2 by substring) —
which is the trade a paraphrase-aware matcher makes, and the search log names the concept and its
score on every request so a fire is a grep away.

The limits are worth stating: the normaliser widens how a concept can be asked for, not what the
lexicon knows, and the lexicon is only as good as the person editing it. Its floors were measured on
eleven concepts and about a hundred queries; a twelfth concept near an existing one has to be checked
against the sibling matrix before it ships — the six added in the review below were, and the
financial-situation cluster is where the ratio binds. In a real product the knowledge itself would come from a maintained
document taxonomy, or from classifying documents by evidence type at ingest. What it should *not* come
from is hoping a bigger model has it.

### Where the lexicon comes from

The reader this is for works at a financial-services firm, will type their own queries and may post
their own documents, so the lexicon is written for a UK adviser's client file in general, not for the
twenty seeded documents, and every entry has to be defensible with a source. The frame is the
adviser's own regulation: the JMLSG Guidance Part I (June 2023, revised August 2025) and the Money
Laundering Regulations 2017 for customer due diligence; the FCA Handbook (COBS 9A.2, COBS 19.1, MCOB
11.6) for the fact-find and affordability; the Mental Capacity Act 2005 and the Office of the Public
Guardian's guidance for acting for someone else; HMRC's AEOI manual for tax residency. Every text was
read in its current form before it was cited and is quoted in short fragments with the paragraph
number. Onfido's public API specification was read as one vendor's concrete instance of an address list
(18 of its 78 document types are accepted as proof of address) and as evidence that such lists vary
between firms — not as an authority.

**Which requirements get a rule: the vocabulary-gap test.** A rule exists where the document type that
satisfies a requirement does not carry the requirement's own words, judged from what such a document
actually says — never from what the seed happens to contain. A CRS self-certification says "tax
residence" and "taxpayer identification number", so the semantic arm finds it unaided and there is no
rule; an official copy of the Land Registry register says "proprietor" and "title absolute", never
"ownership", so there is one. The requirements enumerated from the sources:

| Requirement | Source | Satisfying document types and what they say | Rule? |
| --- | --- | --- | --- |
| Identity of the customer | MLR 2017 reg 28(2); JMLSG 5.3.75 | passport ("Type P", MRZ), photocard licence ("DVLA"), national identity card — never "identity" | yes |
| Residential address | JMLSG 5.3.75, 5.3.76 (5.3.112: address *or* date of birth) | bills and statements carry an address, never "proof of address" | yes |
| Source of funds and wealth | JMLSG 5.5.32, 5.5.6, Annex 5-III/IV | a completion statement says "net proceeds of sale", a will "residue", a grant of probate "administration of the estate", a death certificate "entry of death" | yes |
| Beneficial ownership; ownership and control structure | MLR reg 28(4), reg 5 (over 25 % of shares or votes); Companies Act 2006 Part 21A | a PSC register says "nature of control" and "shares", a register of members "shares held", a confirmation statement "shareholder information" — never "beneficial owner" (the gap is on that phrase; reg 28(4)(c)'s "ownership and control" the PSC register half-carries) | yes |
| Authority to act for the customer | MLR reg 28(10); JMLSG 5.3.99–5.3.101; MCA 2005 s.9, s.16; Powers of Attorney Act 1971 | an LPA says "donor" and "attorney", a general power "I appoint … to be my attorney", a court order "deputy" (and "lacks capacity", so the capacity phrasings reach it unaided), a grant of probate "executors" — none says "authorised to act"; a letter of authority or mandate does ("I authorise"), and needs no rule | yes, for the instruments |
| Income | COBS 9A.2.7R; MCOB 11.6.8R, 11.6.9G, 11.6.13G(1), 11.6.15G(2) | a payslip says "gross pay", a P60 "pay in this employment" (and "Income Tax"), a statement "BACS … SALARY" — the gap is real for the payslip and the statement, partial for the P60, absent for a tax return, which says "income" | yes |
| Assets — liquid assets, investments, real property | COBS 9A.2.7R | a valuation says "holdings" and "total portfolio value", a share certificate "registered holder" — never "assets" | yes |
| Liabilities and regular financial commitments | COBS 9A.2.7R; MCOB 11.6.11G(1), 11.6.13G(2) | a loan agreement says "amount of credit", a credit report "credit accounts", a statement "balance outstanding" — never "liabilities" or "commitments" (the phrasing "outstanding debts" half-meets the statements) | yes |
| Committed and essential expenditure | MCOB 11.6.5R(2), 11.6.10R(2), 11.6.11G(1) | bank statements list debits, a council tax bill instalments — never "expenditure"; an income-and-expenditure form does | yes |
| Interest-only repayment strategy | MCOB 11.6.41R(1)(a), 11.6.45G | an ISA statement says "subscriptions", an endowment statement "target amount (mortgage-linked)" — the closest any comes; none says "repayment strategy" | yes |
| Property ownership and title | lender and conveyancing practice; gov.uk describes the register as showing "who owns it" | the register itself is a "proprietorship register" naming a "proprietor"; an old conveyance recites "as beneficial owner", which the beneficial-ownership probes can reach | yes |
| Dependants | fact-find practice, not text | a birth certificate is a "certified copy of an entry", a Child Benefit letter an award — never "dependant" | passed, then measured out (below) |
| Employment status | MCOB 11.6.9G(2) as a factor, not an evidence requirement | a contract of employment carries the word; payslips are the income rule's | no |
| Marital status | practice | a marriage certificate says "marriage", a final order "the marriage … dissolved" | no |
| Health | practice | a GP report is a "medical report" | no |
| Knowledge and experience; objectives; attitude to risk | COBS 9A.2.1R, 9A.2.9R | no document class; the risk questionnaire says "risk" | no — pinned by a golden |
| Tax residency | HMRC IEIM403140 | the self-certification says "tax residence" | no — the canonical negative, pinned |
| Safeguarded pension benefits | COBS 19.1 | the statement says "safeguarded benefits", "cash equivalent transfer value" | no — "CETV" is a lexical-arm abbreviation gap, stated |
| Existing protection policies | practice | a policy schedule says "sum assured", "policy" | no — the removed life-cover rule |
| Independence of the source; no self-certification of income | MLR reg 28(18); MCOB 11.6.8R(2) | a standard on every list, not a document class: a client's own declaration never belongs on the income rule | constraint, not a rule |
| Affordability of any guarantor | MCOB 11.6.2R ("the customer (and any guarantor…)") | the guarantor's evidence is the income rule's documents in another name; a deed of guarantee is titled with the requirement | no |
| Evidence that the client understands transfer risks | COBS 19.1.1CR(5), 19.1.9AR | the firm's own record; no document type | no |
| PEP and sanctions status; electronic verification | JMLSG 5.5, 5.3.48 | screening and verification reports carry the requirement's words | no |

**The eleven rules, their sources and their order.** Expansions are the document types a UK adviser's
file holds as evidence for the requirement, named as a regulator or an adviser names them, in the order
a client file commonly holds them. Only the first four run for a query naming one concept and two per
rule for a query naming two (`MAX_PROBES` in `QueryExpander` is five, the query included); the later
entries are the accepted list all the same, and raising the ceiling is a code change costing one more
vector scan (about 7 ms) per probe.

| Rule (phrasings) | Source | Expansions in order — the first four run |
| --- | --- | --- |
| evidence of address (7) | JMLSG 5.3.75 "Current council tax demand letter, or statement", "Recent evidence of entitlement to a state or local authority-funded benefit" (the DWP's document is an award letter); 5.3.76 "current bank statements … or utility bills", and a staff record of a home visit as corroboration; the firm's checklist (mortgage statement, HMRC correspondence — the checklist's own name, kept as adviser wording); Onfido (tenancy) | utility bill; bank statement; council tax bill; mortgage statement — benefit award letter; HMRC correspondence; tenancy agreement; record of a home visit |
| evidence of identity (8) | 5.3.75 "Valid passport", "Valid photocard driving licence (full or provisional)", "National Identity card", "Firearms certificate or shotgun licence"; 5.3.90 "requiring copy documents to be certified by an appropriate person" | passport; driving licence; national identity card; certified copy of identity document — firearms certificate |
| evidence of income (6) | MCOB 11.6.13G(1) "payslips and bank statements"; 11.6.9G(5) "payslips, bank statements or tax returns"; 11.6.15G(2) "a pension statement"; gov.uk on the P60 ("as proof of your income if you apply for a loan or a mortgage"); lender practice (employment contract) | payslip; bank statement; pension statement; P60 end of year certificate — tax return; employment contract |
| authority to act for another person (9) | MLR reg 28(10); MCA s.9 ("registered in accordance with Schedule 1"), s.16(2)(b) ("deputy"); gov.uk ("you'll get a court order"; EPAs "made and signed before October 1, 2007 can still be used"); JMLSG 5.3.75 (grant of probate); Powers of Attorney Act 1971 (the general power, for a donor who has capacity) | lasting power of attorney; enduring power of attorney; court order appointing a deputy; grant of probate — general power of attorney |
| source of funds and source of wealth (5) | JMLSG 5.5.32 "a copy of the relevant will", "evidence of conveyancing"; 5.5.6 "inheritance, divorce settlement, property sale"; Annex 5-III/IV "VAT and income tax returns, copies of audited accounts, pay slips, public deeds" | completion statement from a property sale; copy of the will; grant of probate; financial remedy consent order — death certificate; audited accounts; tax return; payslip |
| beneficial ownership of a company or trust (6) | MLR reg 28(4)(a)–(c), reg 5; Companies Act 2006 Part 21A | Companies House confirmation statement; shareholder register; register of people with significant control; trust deed |
| evidence of property ownership (6) | gov.uk on the title register and plan; conveyancing practice | Land Registry title register; title plan; title deeds |
| evidence of assets (6) | COBS 9A.2.7R "their assets, including liquid assets, investments and real property" | investment portfolio valuation; ISA statement; savings account statement; pension statement — property valuation report; share certificate; Land Registry title register; endowment policy statement |
| evidence of liabilities (6) | COBS 9A.2.7R "regular financial commitments"; MCOB 11.6.11G(1) "secured and unsecured loans and credit cards; hire purchase agreements"; 11.6.13G(2) "credit reference agency search or checking credit card or bank statements" | mortgage statement; credit card statement; bank statement; loan agreement — credit report; hire purchase agreement |
| evidence of expenditure (6) | MCOB 11.6.10R(2) "council tax; buildings insurance; ground rent and service charge for leasehold properties"; 11.6.11G(1) "child maintenance"; 11.6.13G(2) | bank statement; credit card statement; council tax bill; utility bill — hire purchase agreement; buildings insurance schedule; ground rent and service charge demand; child maintenance arrangement |
| repayment strategy for an interest-only mortgage (5) | MCOB 11.6.41R(1)(a) "evidence that the customer will have in place a clearly understood and credible repayment strategy"; 11.6.45G "regular deposits into a savings or investment product", "the sale of assets such as another property" | ISA statement; investment portfolio valuation; endowment policy statement; savings account statement — pension statement; property valuation report |

The order is adviser practice, stated rather than measured: the trio JMLSG and every firm's checklist
name first for address; passport before licence before card; payslip and bank statement before the
pension statement (advised clients are pension-heavy, so it sits third) before the annual P60; the LPA
before the pre-2007 enduring power before a deputyship order; the property sale before the will for
source of wealth. Where the fourth slot falls is where the budget cuts, and that is the one place the
seed does not and the code does decide. Two constraints ride on the lists: MCOB 11.6.46E(2) names "an
intention on the part of the customer to utilise an expected, but uncertain, inheritance" as tending to
show contravention of the repayment-strategy rule, so `copy of the will` and `grant of probate` stay on
the source-of-funds rule and never migrate to the repayment one; and the address and expenditure rules
share their first three documents (bank statement, council tax bill, utility bill), so a query that fired
both would spend its budget on the same three types — none of the address goldens does, and the matrix
puts the two rules at 0.31.

**How a probe is proved right: the held-out fixtures.** The seed corpus is twenty documents and lacks
most of these types — no payslip, no passport, no court order, no title register — so "the probe reaches
nothing seeded" says nothing about the probe. `EvidenceFixtureTest` holds one short synthetic document
per type the seed lacks (45 of them under `src/test/resources/fixtures/evidence`, each written the way
the real document reads: a payslip's fields, a court order's "IT IS ORDERED THAT", a register's
"PROPRIETOR"), ingests them through the real path inside a transaction that rolls back — they are never
seeded into the demo — and asserts that every expansion places its own document on the semantic arm's
page (the absolute floor, then 0.70 of the best hit). Sixty-seven probe checks over forty-eight document
types (a type on several rules is checked on each); sixty-four place their document, fifty-one of them
at rank 1. The one type that misses is listed, not tuned around: `pension statement` — MCOB's own name for the
document — scores the seeded scheme statement 0.46 and *Suitability Report: Pension Consolidation* 0.68,
and the relative floor cuts the statement; a near-synonym title outranks the real thing, which is the
semantic arm's limit rather than the probe's. Four wordings changed on this test or on the document's own name, none on seed
reach: the divorce document is the court's "financial remedy consent order"; `register of members` (the Companies Act's name) scored 0.33 against its own register and became
the adviser's `shareholder register` (0.46); `service charge demand` lost its page to the adviser's own
fee schedule and became MCOB's `ground rent and service charge demand` (0.61); and a `birth certificate`
probe could not place a birth certificate, whose own words are "certified copy of an entry" — a document
type that never names itself, recorded because the gap test cannot see it. `HMRC correspondence` passes
narrowly (rank 9 of 9 at 0.32): it is the checklist's own name for a genre rather than a document, and
the coding notice the fixture picks is a letter like many others.

**What the seed does and does not decide.** The seed is a smoke test: the brief's two examples work
("address proof" and "proof of address" return the bill, fifth, behind four documents that also state
an address or list what is accepted) and the golden queries do not regress unexplained. It decides no
part of the list. An earlier pass of this review did select against it — `tenancy agreement`, `P60`,
`tax return` and `employment contract` were dropped for reaching nothing seeded, and the income probe
was worded toward the one seeded salary line — and was reversed: those entries are back in practice
order and the probe is `bank statement`, the type MCOB names. Only two removals stand, and both stand
on the texts: the first version's "life cover paid on death" rule, because nothing in JMLSG, COBS or
MCOB makes life cover an evidentiary requirement — its golden is rank 1 without it; and a dependants
rule, which passed the gap test but measured out three ways — its phrasings sat at 0.77 of the
liabilities rule's (both written with "the client"), its main document type never names itself, and its
basis is fact-find practice rather than text. The source-of-funds rule has no seeded document to earn a
golden with (the computation of capital gains is HMRC paperwork, not a completion statement), so its
proof is the fixture test and its page is printed by `QueryExpanderTest` every run.

**The sibling matrix at eleven rules, and the design's limit.** `ConceptFloorTest` prints, for every
pair of rules, the highest score any exact phrasing of the row reaches against the column as a share of
its own score. At eleven rules:

| row fires column at | addr | id | inc | auth | sof | benef | prop | assets | liab | exp | repay |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| address | – | 0.64 | 0.45 | 0.34 | 0.22 | 0.26 | 0.55 | 0.50 | 0.33 | 0.31 | 0.19 |
| identity | 0.64 | – | 0.51 | 0.39 | 0.22 | 0.28 | 0.49 | 0.53 | 0.33 | 0.33 | 0.16 |
| income | 0.45 | 0.51 | – | 0.30 | 0.54 | 0.31 | 0.55 | **0.70** | 0.57 | **0.71** | 0.46 |
| authority to act | 0.34 | 0.39 | 0.30 | – | 0.21 | 0.42 | 0.36 | 0.32 | 0.27 | 0.29 | 0.20 |
| source of funds | 0.22 | 0.22 | 0.54 | 0.21 | – | 0.49 | 0.43 | 0.54 | 0.45 | 0.50 | 0.42 |
| beneficial ownership | 0.26 | 0.28 | 0.31 | 0.42 | 0.49 | – | 0.66 | 0.43 | 0.33 | 0.24 | 0.31 |
| property ownership | 0.55 | 0.49 | 0.55 | 0.36 | 0.43 | 0.66 | – | **0.69** | 0.46 | 0.37 | 0.32 |
| assets | 0.50 | 0.53 | **0.70** | 0.32 | 0.54 | 0.43 | **0.69** | – | 0.66 | 0.60 | 0.42 |
| liabilities | 0.33 | 0.33 | 0.57 | 0.27 | 0.45 | 0.33 | 0.46 | 0.66 | – | **0.73** | 0.57 |
| expenditure | 0.31 | 0.33 | **0.71** | 0.29 | 0.50 | 0.24 | 0.37 | 0.60 | **0.73** | – | 0.54 |
| repayment strategy | 0.19 | 0.16 | 0.46 | 0.20 | 0.42 | 0.31 | 0.32 | 0.42 | 0.57 | 0.54 | – |

No exact phrasing fires two rules (the strongest share is 0.73, under the 0.75 ratio), so each rule's
own vocabulary is its own. The crowding is in the cluster the regulation itself groups — COBS 9A.2.7R's
"regular income", "assets" and "regular financial commitments", with the two rules whose evidence is
the same documents — and it shows on natural paraphrases, which sit lower than exact phrasings and so
leave more room under them: "evidence of outstanding borrowing" fires liabilities at 0.71 and the
repayment rule at 0.62 (0.87 of it); "evidence of committed spending" fires expenditure at 0.77 and
assets at 0.61 (0.79); "evidence of where the invested money came from" fires source of funds at 0.74,
assets at 0.61 (0.82) and expenditure; "someone to run his finances if he becomes unable to" is won by
expenditure over the authority rule by 0.005. The first round of this enlargement, written with "the
client" and pronouns in the new phrasings, was worse — liabilities and dependants at 0.77 of each other,
"confirm the client is who he says he is" firing seven rules — and rewording removed that part; what
remains is the cluster's nature in this embedding space. Its cost is real: a rider takes probe slots
from the intended rule (a query firing three rules gives each one probe, then the first a second), which
is how the source-of-funds golden fell from rank 2 to rank 6 before it was withdrawn as unearnable on
this seed.

The ratio is not lowered for the cluster: at 0.70 the exact phrasings of income and assets would fire
each other, and the weakest legitimate two-concept query already sits at 0.755. The resolution this
design points at is a **hierarchy of rules**, which is a code change: a parent concept "financial
situation" (9A.2.7R's own scope) whose phrasings are the cluster's shared vocabulary and whose
expansions are the merged list's first four (bank statement, payslip, pension statement, mortgage
statement), and child rules — income, assets, liabilities, expenditure — that fire when a query clears
the child's own floor and then take the whole budget. A query that is ambiguous across the cluster runs
the parent's four; a query that names one member runs that member's four, with no rider. The no-code
fallback is one merged rule, and it was rejected here on its cost: under a five-probe ceiling a merged
list runs the same four documents for "evidence of assets" and "what he owes" as for "proof of income",
so a debts query would never reach a loan agreement or a credit report. Until the hierarchy exists the
cluster ships as separate rules, the riders inside it are allowed by declaration in `ConceptFloorTest`,
and the matrix is printed every run so the numbers stay in view.

**The limit the lexicon cannot cross.** "proof of income" puts the bank statement first in the
semantic arm — from 13th before the income rule was re-pointed — and sixth in the fused result. Five
documents contain both "proof" and "income" for the keyword and sparse arms, and reciprocal rank
fusion with k = 60 ranks any document two arms find above one that a single arm finds, whatever its
position (2/65 against 1/61). Phrased "evidence of earnings" the statement is fourth and "what shows
how much he earns" fourth: those are the goldens, and the sixth place is printed by `QueryExpanderTest`
every run so it stays visible.

**Why this is configuration and not something a model could learn.** The texts set a standard and
leave the list to the firm. JMLSG 5.3.112 says the guidance "does not require that in all cases a
customer's address should be verified"; Part I sets no numeric recency for an identity or address
document — only "current" and "recent" — where the seeded onboarding checklist wants one "dated within
the last three months"; that checklist refuses the mobile phone bill Onfido's proof-of-address list
accepts and takes a national identity card "issued by an EEA state" only, a restriction 5.3.77 allows;
MCOB 11.6.8R accepts income evidence "whether document-based or derived through the use of automated
systems", and 11.6.50R(2) pushes "the evidential requirements and other controls" for repayment
strategies into the lender's own policy; MLR reg 28(18) requires every verification document to come
from "a reliable source which is independent of the person whose identity is being verified", which is
why a client's own income declaration is on no list. Two firms reading the same text produce two lists;
this file holds one firm's, and the fixture set is what that firm's list does on documents of every
type it names.

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
| keyword | 17/34 | 0.500 |
| sparse | 26/34 | 0.724 |
| semantic | 33/34 | 0.789 |
| keyword + semantic — the two-arm system | 34/34 | 0.780 |
| keyword + sparse | 26/34 | 0.723 |
| **keyword + sparse + semantic** | **34/34** | **0.809** |
| … with the lexicon's probes through the sparse arm too | 34/34 | 0.834 |

Three arms is the only configuration that finds every golden document in the top five with the
best reciprocal rank; the semantic arm alone misses one and the two-arm system trails by 0.03. The
eight queries added with the lexicon review are mostly documents that only the lexicon-widened
semantic arm reaches (the bank statement for "evidence of earnings"), and for those every extra arm can
only add competitors — a document two arms find outranks one a single arm finds, whatever its position
([the fusion limit](#where-the-lexicon-comes-from)). On the 26 queries before that review the same
table read 0.821 for two arms and 0.841 for three, and the probes-through-sparse row 0.860; that row
still costs the page length described under [Sparse](#sparse-learned-term-weights) and stays unshipped.
The ablation applies the product's own tie-break — agreement, then the most literal arm, then title —
so its three-arm column is the evaluation table's.

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

`golden-queries.json` holds 41 queries an advisor might type, each with the one result that must come
back. `SearchQualityTest` asserts every one lands in the top five and prints mean reciprocal rank, so
a change that keeps every query passing while pushing results down the list is still visible. Three
document queries were added with the sparse arm, each a partial-term query the lexical AND cannot
answer ("electricity supplier statement" — `supplier` is absent); on the original eighteen the
two-arm system scored 0.778 and the three-arm one 0.835. Five more came with the semantic normaliser:
two paraphrases of the address requirement that no phrase in the lexicon appears in — unreachable
before it — and one paraphrase per other concept that already passed on meaning alone, pinned so a
concept firing on it never costs the rank. Eight more came with the review of the lexicon against its
sources ([above](#where-the-lexicon-comes-from)): two the income rule earns ("evidence of earnings"
and "what shows how much he earns" — the bank statement says neither word) and six pins: requirements
the texts name for which no rule exists, a two-concept query, and the mortgage offer the firm's own
list reaches, placed where meaning and the sparse arm already put them. The source-of-funds rule earns
none: the seed holds no completion statement, will or grant of probate, and its golden on a
seed-fitted probe was withdrawn with that probe. The hard case is stated as measured: for "documents
that show where the client lives" the normaliser reaches the address rule at 0.81 and the bank
statement, which carries the address, ranks second; the electricity bill is fourth, behind the
statement and two partial sparse matches.

| Set | Queries | hit@5 | MRR |
| --- | --- | --- | --- |
| Documents | 34 | 34/34 | 0.809 (0.837 on the 26 before the lexicon review, 0.859 on the original 21) |
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
offer; now it returns the documents that state one — the bank statement, the mortgage offer the
firm's own list reaches, the bill — and, for "proof of address", the checklist that lists what is
accepted.

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
0.470, the weakest paraphrase that must fire 0.509, so the absolute floor is **0.50**; a two-concept
query's second concept scores at least 0.755 of its first, an exact phrasing's strongest sibling at
most 0.73, so the relative floor is **0.75**. `ConceptFloorTest` asserts both from both sides and
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
