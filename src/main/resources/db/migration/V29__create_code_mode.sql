CREATE TABLE project_code_mode (
    project_id INTEGER PRIMARY KEY REFERENCES project (id) ON DELETE CASCADE,
    enabled    INTEGER NOT NULL DEFAULT 0,
    servers    TEXT NOT NULL DEFAULT '[]'
);

CREATE TABLE code_mode_isolation (
    id      INTEGER PRIMARY KEY CHECK (id = 1),
    backend TEXT NOT NULL
);

INSERT INTO code_mode_isolation (id, backend) VALUES (1, 'GRAALJS');

CREATE TABLE code_mode_call (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id  INTEGER NOT NULL,
    session_id  TEXT,
    server      TEXT NOT NULL,
    tool        TEXT NOT NULL,
    decision    TEXT NOT NULL,
    status      TEXT NOT NULL,
    duration_ms INTEGER NOT NULL,
    at          INTEGER NOT NULL
);

CREATE INDEX idx_code_mode_call_at ON code_mode_call (at);
