-- init-pgvector.sql
-- Runs automatically on first postgres container start.
-- Enables the pgvector and uuid-ossp extensions required by Spring AI.

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Spring AI pgvector store will create the vector table on startup
-- when spring.ai.vectorstore.pgvector.initialize-schema=true
-- No manual table creation needed here.
