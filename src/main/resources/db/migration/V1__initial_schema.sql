-- pg_trgm is a trusted extension; vector is not, so the connecting role must be a superuser the
-- first time this migration runs. The compose Postgres user is one, and managed providers
-- (Neon, RDS, Supabase) allow both from their extension whitelist.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE clients (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    first_name   text NOT NULL,
    last_name    text NOT NULL,
    email        text NOT NULL,
    description  text,
    social_links text[] NOT NULL DEFAULT '{}',
    created_at   timestamptz NOT NULL DEFAULT now(),
    -- One lowercased haystack per client so a single trigram index covers name, email and
    -- description. coalesce is required because `||` yields NULL if any operand is NULL.
    -- social_links is left out on purpose: array_to_string is STABLE, not IMMUTABLE, so Postgres
    -- rejects it in a generated column.
    search_text  text GENERATED ALWAYS AS (
        lower(first_name || ' ' || last_name || ' ' || email || ' ' || coalesce(description, ''))
    ) STORED
);

-- Email is the identity key advisors search by, so duplicates are rejected rather than merged.
CREATE UNIQUE INDEX uq_clients_email ON clients (lower(email));

-- gin_trgm_ops serves both search arms: the LIKE '%q%' substring scan and the `<%` word-similarity
-- scan. GiST would only be needed for index-assisted ORDER BY distance, which this API does not do.
CREATE INDEX idx_clients_search_trgm ON clients USING gin (search_text gin_trgm_ops);

CREATE TABLE documents (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id  uuid NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    title      text NOT NULL,
    content    text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    -- The two-argument to_tsvector is IMMUTABLE and therefore legal in a generated column; the
    -- one-argument form depends on default_text_search_config and would be rejected.
    fts        tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('english', coalesce(title, '')), 'A') ||
        setweight(to_tsvector('english', coalesce(content, '')), 'B')
    ) STORED
);

CREATE INDEX idx_documents_client ON documents (client_id);
CREATE INDEX idx_documents_fts ON documents USING gin (fts);

CREATE TABLE document_chunks (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    document_id     uuid NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    chunk_index     int NOT NULL,
    content         text NOT NULL,
    embedding       vector(384) NOT NULL,
    -- Which model produced this row. Checked at startup so a model swap fails loudly instead of
    -- silently comparing vectors from two different embedding spaces.
    embedding_model text NOT NULL,
    UNIQUE (document_id, chunk_index)
);

CREATE INDEX idx_chunks_document ON document_chunks (document_id);

-- No ANN index (HNSW/IVFFlat) on purpose: at this corpus size an exact scan is sub-millisecond and
-- cannot miss a neighbour, which an approximate index can. See README section "Why no ANN index".
