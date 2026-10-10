-- LoomVault persistence schema, version 1.
--
-- Portability rules (the same script must run on PostgreSQL 12+ and H2 2.x):
--   * time columns are BIGINT epoch millis, never TIMESTAMP
--   * structured JSON lives in TEXT columns, never JSONB
--   * identifiers are VARCHAR, never UUID / SERIAL
--   * no reserved words are used as column names (entry_seq / entry_type instead of seq / type)

CREATE TABLE loomvault_execution (
    -- Stable execution id supplied by the engine.
    id               VARCHAR(64)  NOT NULL,
    workflow_id      VARCHAR(128) NOT NULL,
    workflow_version VARCHAR(64)  NOT NULL,
    -- ExecutionStatus enum name (PENDING, RUNNING, ...).
    status           VARCHAR(32)  NOT NULL,
    inputs_json      TEXT,
    outputs_json     TEXT,
    usage_json       TEXT,
    failure_code     VARCHAR(64),
    failure_reason   TEXT,
    -- Epoch millis. created_at is always present; started/finished may be null.
    created_at       BIGINT       NOT NULL,
    started_at       BIGINT,
    finished_at      BIGINT,
    resume_count     INTEGER      NOT NULL DEFAULT 0,
    -- Optimistic-locking counter, bumped on every successful save.
    revision         BIGINT       NOT NULL DEFAULT 0,
    metadata_json    TEXT,
    CONSTRAINT pk_loomvault_execution PRIMARY KEY (id)
);

-- Dashboard and recovery queries filter by lifecycle state.
CREATE INDEX idx_loomvault_execution_status ON loomvault_execution (status);
-- "most recent first" listings order by creation time.
CREATE INDEX idx_loomvault_execution_created_at ON loomvault_execution (created_at);

CREATE TABLE loomvault_node_state (
    execution_id  VARCHAR(64)  NOT NULL,
    node_id       VARCHAR(128) NOT NULL,
    -- NodeStatus enum name (PENDING, RUNNING, COMPLETED, ...).
    status        VARCHAR(32)  NOT NULL,
    attempt       INTEGER      NOT NULL DEFAULT 0,
    output_json   TEXT,
    error_code    VARCHAR(64),
    error_message TEXT,
    started_at    BIGINT,
    finished_at   BIGINT,
    usage_json    TEXT,
    CONSTRAINT pk_loomvault_node_state PRIMARY KEY (execution_id, node_id)
);

CREATE TABLE loomvault_journal (
    execution_id VARCHAR(64)  NOT NULL,
    -- Per-execution monotonic sequence, starting at 1. "seq" is avoided as a reserved word.
    entry_seq    BIGINT       NOT NULL,
    -- JournalEntryType enum name (EXECUTION_STARTED, NODE_COMPLETED, ...). "type" is avoided.
    entry_type   VARCHAR(64)  NOT NULL,
    node_id      VARCHAR(128),
    payload_json TEXT,
    -- Epoch millis.
    ts           BIGINT       NOT NULL,
    CONSTRAINT pk_loomvault_journal PRIMARY KEY (execution_id, entry_seq)
);

-- Ordered replay reads scan (execution_id, entry_seq); declared explicitly for clarity even
-- though the composite primary key already makes this range cheap.
CREATE INDEX idx_loomvault_journal_execution_seq ON loomvault_journal (execution_id, entry_seq);
