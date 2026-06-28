-- docker/postgres/init-rag-pgvector.sql

CREATE EXTENSION IF NOT EXISTS vector;

CREATE SCHEMA IF NOT EXISTS rag AUTHORIZATION rag_app;

CREATE TABLE IF NOT EXISTS rag.document_chunks (
                                                   id UUID PRIMARY KEY,
                                                   content TEXT NOT NULL,
                                                   metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
                                                   embedding VECTOR(768) NOT NULL,          -- nomic-embed-text = 768 dims
    source VARCHAR(1000),
    page_number INTEGER,
    chunk_index INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
    );

CREATE INDEX IF NOT EXISTS document_chunks_embedding_hnsw_idx
    ON rag.document_chunks
    USING hnsw (embedding vector_cosine_ops);

CREATE INDEX IF NOT EXISTS document_chunks_metadata_gin_idx
    ON rag.document_chunks
    USING gin (metadata);

CREATE INDEX IF NOT EXISTS document_chunks_source_idx
    ON rag.document_chunks (source);

CREATE INDEX IF NOT EXISTS document_chunks_created_at_idx
    ON rag.document_chunks (created_at);

GRANT USAGE ON SCHEMA rag TO rag_app;

GRANT SELECT, INSERT, UPDATE, DELETE
    ON TABLE rag.document_chunks
    TO rag_app;

ALTER DEFAULT PRIVILEGES IN SCHEMA rag
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO rag_app;