-- pg_trgm is a trusted extension; vector is not, so the connecting role must be a superuser the
-- first time this migration runs. The compose Postgres user is one, and managed providers
-- (Neon, RDS, Supabase) allow both from their extension whitelist.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS vector;

-- Field bounds are enforced twice on purpose. The API is the first validator and returns per-field
-- 400s; these varchar limits and CHECK constraints are the backstop for any writer that is not the
-- API — a migration, a bulk import, an ad-hoc psql session. The limits equal the API's @Size
-- bounds. (The API's are counted in UTF-16 units and Postgres counts code points, so API-valid
-- input always fits here; the API is strictly the tighter gate.)
--
-- Ids are UUIDv7 (native in Postgres 18): the leading 48 bits are a unix-ms timestamp, so ids are
-- time-ordered and primary-key inserts stay append-mostly instead of scattering across the B-tree
-- the way fully random v4 ids do.

CREATE TABLE clients (
    id           uuid PRIMARY KEY DEFAULT uuidv7(),
    first_name   varchar(200) NOT NULL,
    last_name    varchar(200) NOT NULL,
    -- 320 = 64 (local part) + 1 + 255 (domain), the classic upper bound for an email address.
    email        varchar(320) NOT NULL,
    description  varchar(5000),
    -- The per-element bound (500 chars) stays API-only: an element typmod would not survive
    -- array functions, and nothing searches or indexes this column.
    social_links text[] NOT NULL DEFAULT '{}',
    created_at   timestamptz NOT NULL DEFAULT now(),
    -- One lowercased haystack per client so a single trigram index covers name, email and
    -- description. coalesce is required because `||` yields NULL if any operand is NULL.
    -- social_links is left out on purpose: array_to_string is STABLE, not IMMUTABLE, so Postgres
    -- rejects it in a generated column.
    search_text  text GENERATED ALWAYS AS (
        lower(first_name || ' ' || last_name || ' ' || email || ' ' || coalesce(description, ''))
    ) STORED,
    -- The API trims before insert, so a blank value here means genuinely blank, not padding.
    CONSTRAINT clients_first_name_not_blank CHECK (btrim(first_name) <> ''),
    CONSTRAINT clients_last_name_not_blank  CHECK (btrim(last_name) <> ''),
    CONSTRAINT clients_email_not_blank      CHECK (btrim(email) <> '')
);

-- Email is the identity key advisors search by, so duplicates are rejected rather than merged.
CREATE UNIQUE INDEX uq_clients_email ON clients (lower(email));

-- gin_trgm_ops serves both search arms: the LIKE '%q%' substring scan and the `<%` word-similarity
-- scan. GiST would only be needed for index-assisted ORDER BY distance, which this API does not do.
CREATE INDEX idx_clients_search_trgm ON clients USING gin (search_text gin_trgm_ops);

CREATE TABLE documents (
    id         uuid PRIMARY KEY DEFAULT uuidv7(),
    client_id  uuid NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    title      varchar(500) NOT NULL,
    -- content stays text with a CHECK rather than varchar(100000): the cap mirrors the
    -- configurable ingest.max-content-length, and a business rule frozen into a column type
    -- cannot be commented or found by name. The configured value must never exceed this ceiling.
    content    text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    -- The two-argument to_tsvector is IMMUTABLE and therefore legal in a generated column; the
    -- one-argument form depends on default_text_search_config and would be rejected.
    fts        tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('english', coalesce(title, '')), 'A') ||
        setweight(to_tsvector('english', coalesce(content, '')), 'B')
    ) STORED,
    CONSTRAINT documents_title_not_blank   CHECK (btrim(title) <> ''),
    CONSTRAINT documents_content_not_blank CHECK (btrim(content) <> ''),
    CONSTRAINT documents_content_length    CHECK (length(content) <= 100000)
);

CREATE INDEX idx_documents_client ON documents (client_id);
CREATE INDEX idx_documents_fts ON documents USING gin (fts);

CREATE TABLE document_chunks (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    document_id     uuid NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    chunk_index     int NOT NULL,
    -- Produced by the chunker, whose own token and character budgets bound it; no user reaches
    -- this table directly, so it carries no varchar/CHECK of its own.
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
