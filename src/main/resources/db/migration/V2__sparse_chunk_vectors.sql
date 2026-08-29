-- SPLADE term weights per chunk, beside the dense vector, so the sparse arm scores the same rows
-- with the same per-document reduction as the semantic arm. sparsevec arrived in pgvector 0.7.0;
-- pgvector/pgvector:0.8.6-pg18 ships it, and on an older extension this fails with "type sparsevec
-- does not exist", which is the right outcome. The typmod is the bert-base-uncased WordPiece
-- vocabulary both models share: the text format numbers indices from 1, so vocabulary id 0 is
-- stored as index 1 and id 30521 as 30522. A value costs 8 bytes per non-zero plus 16 — the
-- dimension itself is free — and on the seeded corpus a chunk activates 145 to 666 terms, so a
-- sparse vector is about the size of the vector(384) beside it.
--
-- NOT NULL without a default is legal only on an empty table, and that is deliberate: no instance
-- held data when this shipped, so the columns arrive as invariants rather than as a backfill. An
-- instance with rows fails this migration with "column contains null values" and has to be
-- recreated (docker compose down -v) — the same fail-fast the model-id check gives a model swap,
-- because a corpus the sparse arm could only half see would degrade search silently.
ALTER TABLE document_chunks
    ADD COLUMN sparse_embedding sparsevec(30522) NOT NULL,
    -- Which sparse model produced this row, checked at startup exactly as embedding_model is:
    -- weights from two checkpoints share a vocabulary axis but not a scale, so mixing them ranks
    -- plausibly and wrongly.
    ADD COLUMN sparse_model     text NOT NULL;

-- No index on sparse_embedding, for the reason V1 gives for embedding: the exact scan cannot miss
-- a match and is cheap at this size. Two more reasons specific to this column. pgvector's only
-- sparse index is HNSW (sparsevec_ip_ops for <#>; IVFFlat has no sparsevec support), and an HNSW
-- index serves `ORDER BY x <#> q LIMIT k` over chunks — it cannot serve the per-document min() the
-- search actually runs, so indexing would mean going back to the chunk-bounded shortlist the
-- semantic arm rejected. And it accepts at most 1,000 non-zeros per value, which is what
-- sparse.max-terms keeps every stored vector under, so that door stays open.
