-- Baseline schema; later changes go into V2__*.sql and up.

-- Teams (seeded "Dev team") and projects

CREATE TABLE team (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    name       TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE team_role (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    team_id     INTEGER NOT NULL REFERENCES team (id) ON DELETE CASCADE,
    name        TEXT NOT NULL,
    description TEXT NOT NULL,
    prompt      TEXT NOT NULL,
    model       TEXT,
    effort      TEXT,
    tools       TEXT,
    is_lead     INTEGER NOT NULL DEFAULT 0,
    position    INTEGER NOT NULL,
    UNIQUE (team_id, name)
);

INSERT INTO team (id, name) VALUES (1, 'Dev team');

INSERT INTO team_role (id, team_id, name, description, prompt, model, effort, tools, is_lead, position) VALUES
(1, 1, 'architect', 'Leads the task: splits it into steps, decides the design, reviews the result. Not a subagent, this is the main session.',
 'You lead the team. Break the task into small steps, delegate implementation to the developer and verification to the tester, then review what they report and decide whether the task is done.',
 'opus', 'high', NULL, 1, 0),
(2, 1, 'developer', 'Use to write or change code: implement a feature, fix a bug, refactor. Give it one concrete, self-contained step.',
 'You are a careful developer. Implement exactly the step you are given, keep changes small and consistent with the surrounding code, and report which files you changed.',
 'sonnet', 'medium', NULL, 0, 1),
(3, 1, 'tester', 'Use to check work: run the build and tests, try the change, and report failures. Use after the developer finished a step.',
 'You are a tester. Run the relevant build and tests, verify the change behaves as asked, and report precisely what passed and what failed. Do not change production code.',
 'haiku', 'low', 'Read, Grep, Glob, Bash', 0, 2);

CREATE TABLE project (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT NOT NULL,
    path        TEXT NOT NULL UNIQUE,
    created_at  TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    model       TEXT,
    effort      TEXT,
    team_id     INTEGER REFERENCES team (id) ON DELETE SET NULL,
    is_group    INTEGER NOT NULL DEFAULT 0,
    group_id    INTEGER REFERENCES project (id) ON DELETE SET NULL,
    trust_level TEXT
);

CREATE INDEX idx_project_group_id ON project (group_id);

CREATE TABLE group_lsp_override (
    group_id INTEGER PRIMARY KEY REFERENCES project (id) ON DELETE CASCADE
);

CREATE TABLE trust_settings (
    id            INTEGER PRIMARY KEY CHECK (id = 1),
    default_level TEXT NOT NULL
);

INSERT INTO trust_settings (id, default_level) VALUES (1, 'ASK');

CREATE TABLE custom_ide (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    name       TEXT NOT NULL UNIQUE COLLATE NOCASE,
    executable TEXT NOT NULL,
    arguments  TEXT NOT NULL DEFAULT ''
);

-- Hook events

CREATE TABLE hook_event (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    astrolabe_session_id TEXT NOT NULL,
    event                TEXT NOT NULL,
    agent_id             TEXT,
    claude_session_id    TEXT,
    payload              TEXT NOT NULL,
    received_at          TEXT NOT NULL
);

CREATE INDEX idx_hook_event_session ON hook_event (astrolabe_session_id, id);

-- Run configurations, prompt templates, task worktrees

CREATE TABLE run_configuration (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id INTEGER NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    name       TEXT NOT NULL,
    command    TEXT NOT NULL,
    directory  TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirmed  INTEGER NOT NULL DEFAULT 1,
    UNIQUE (project_id, name)
);

CREATE TABLE prompt_template (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    name            TEXT NOT NULL,
    text            TEXT NOT NULL,
    project_id      INTEGER REFERENCES project (id) ON DELETE CASCADE,
    default_team_id INTEGER REFERENCES team (id) ON DELETE SET NULL,
    created_at      TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    description     TEXT NOT NULL DEFAULT ''
);

CREATE TABLE task_worktree (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id  INTEGER NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    path        TEXT NOT NULL UNIQUE,
    branch      TEXT NOT NULL,
    base_branch TEXT NOT NULL,
    task        TEXT NOT NULL,
    created_at  TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_task_worktree_project ON task_worktree (project_id);

-- Session history search

CREATE TABLE transcript_file (
    id INTEGER PRIMARY KEY,
    path TEXT UNIQUE NOT NULL,
    claude_session_id TEXT NOT NULL,
    cwd TEXT NOT NULL DEFAULT '',
    indexed_bytes INTEGER NOT NULL DEFAULT 0,
    size_bytes INTEGER NOT NULL DEFAULT 0,
    modified_at INTEGER NOT NULL DEFAULT 0,
    last_entry_at INTEGER NOT NULL DEFAULT 0,
    entry_count INTEGER NOT NULL DEFAULT 0,
    sensitive_tool_ids TEXT NOT NULL DEFAULT ''
);

CREATE INDEX idx_transcript_file_last_entry ON transcript_file (last_entry_at, id);

CREATE VIRTUAL TABLE transcript_entry_fts USING fts5(
    text,
    role UNINDEXED,
    file_id UNINDEXED,
    entry_uuid UNINDEXED,
    line_offset UNINDEXED,
    at UNINDEXED,
    tokenize = 'unicode61 remove_diacritics 2'
);

CREATE TABLE transcript_entry_ref (
    entry_id INTEGER PRIMARY KEY,
    file_id INTEGER NOT NULL
);

CREATE INDEX idx_transcript_entry_ref_file ON transcript_entry_ref (file_id);

-- Usage analytics

CREATE TABLE usage_sample (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    at INTEGER NOT NULL,
    astrolabe_session_id TEXT NOT NULL,
    claude_session_id TEXT,
    project_id INTEGER NOT NULL,
    model TEXT,
    effort TEXT,
    context_percent REAL,
    cost_usd REAL NOT NULL,
    cost_delta REAL NOT NULL
);
CREATE INDEX idx_usage_sample_at ON usage_sample (at);
CREATE INDEX idx_usage_sample_claude_session ON usage_sample (claude_session_id, at);

CREATE TABLE rate_limit_sample (
    at INTEGER NOT NULL,
    five_hour_pct REAL,
    five_hour_resets_at INTEGER,
    seven_day_pct REAL,
    seven_day_resets_at INTEGER
);
CREATE INDEX idx_rate_limit_sample_at ON rate_limit_sample (at);

CREATE TABLE usage_daily (
    day TEXT NOT NULL,
    project_id INTEGER NOT NULL,
    model TEXT NOT NULL,
    cost_usd REAL NOT NULL,
    sessions INTEGER NOT NULL,
    samples INTEGER NOT NULL,
    PRIMARY KEY (day, project_id, model)
);

CREATE TABLE usage_alert (
    alert_key TEXT PRIMARY KEY,
    fired_at INTEGER NOT NULL
);

-- Author catalog decisions

CREATE TABLE author_item_decision (
    item_id    TEXT NOT NULL PRIMARY KEY,
    status     TEXT NOT NULL CHECK (status IN ('INSTALLED', 'DECLINED')),
    version    INTEGER NOT NULL,
    decided_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Project presets

CREATE TABLE project_preset (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    name            TEXT    NOT NULL UNIQUE,
    description     TEXT    NOT NULL,
    bundled_key     TEXT    UNIQUE,
    bundled_version INTEGER,
    edited          INTEGER NOT NULL DEFAULT 0,
    deleted         INTEGER NOT NULL DEFAULT 0,
    revision        INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE project_preset_component (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    preset_id INTEGER NOT NULL REFERENCES project_preset (id) ON DELETE CASCADE,
    position  INTEGER NOT NULL,
    kind      TEXT    NOT NULL CHECK (kind IN ('MARKETPLACE', 'PLUGIN', 'SKILL', 'AGENT', 'MCP_SERVER', 'INSTRUCTION', 'SETTINGS', 'FILE', 'CODE_MODE')),
    name      TEXT    NOT NULL,
    payload   TEXT    NOT NULL,
    UNIQUE (preset_id, kind, name)
);

CREATE TABLE project_preset_application (
    project_id INTEGER NOT NULL,
    preset_id  INTEGER NOT NULL REFERENCES project_preset (id) ON DELETE CASCADE,
    revision   INTEGER NOT NULL,
    applied_at TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, preset_id)
);

CREATE TRIGGER project_preset_application_project_removed AFTER DELETE ON project
BEGIN
    DELETE FROM project_preset_application WHERE project_id = OLD.id;
END;

CREATE TABLE project_preset_offer_declined (
    project_id INTEGER PRIMARY KEY
);

CREATE TRIGGER project_preset_offer_declined_project_removed AFTER DELETE ON project
BEGIN
    DELETE FROM project_preset_offer_declined WHERE project_id = OLD.id;
END;

-- Screenshot review

CREATE TABLE review (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id     INTEGER NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    astrolabe_session_id TEXT,
    title          TEXT NOT NULL,
    note           TEXT NOT NULL DEFAULT '',
    status         TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT', 'APPROVED')),
    created_at     TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    settled_at     TEXT
);

CREATE TABLE review_image (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    review_id INTEGER NOT NULL REFERENCES review (id) ON DELETE CASCADE,
    position  INTEGER NOT NULL,
    label     TEXT NOT NULL DEFAULT '',
    file_name TEXT NOT NULL,
    width     INTEGER NOT NULL,
    height    INTEGER NOT NULL
);

CREATE TABLE review_mark (
    id       INTEGER PRIMARY KEY AUTOINCREMENT,
    image_id INTEGER NOT NULL REFERENCES review_image (id) ON DELETE CASCADE,
    kind     TEXT NOT NULL CHECK (kind IN ('RECTANGLE', 'ELLIPSE', 'PIN', 'FREEFORM')),
    x        REAL NOT NULL,
    y        REAL NOT NULL,
    w        REAL NOT NULL,
    h        REAL NOT NULL,
    comment  TEXT NOT NULL DEFAULT '',
    points   TEXT NOT NULL DEFAULT '[]'
);

CREATE INDEX idx_review_project ON review (project_id);
CREATE INDEX idx_review_created_at ON review (created_at);
CREATE INDEX idx_review_image_review ON review_image (review_id);
CREATE INDEX idx_review_mark_image ON review_mark (image_id);

-- Web review

CREATE TABLE web_review_revision (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id     INTEGER NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    number         INTEGER NOT NULL,
    astrolabe_session_id TEXT,
    note           TEXT NOT NULL DEFAULT '',
    status         TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT', 'APPROVED')),
    created_at     TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at        TEXT,
    settled_at     TEXT,
    UNIQUE (project_id, number)
);

CREATE TABLE web_review_page (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id INTEGER NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    page_key   TEXT NOT NULL,
    last_url   TEXT NOT NULL,
    title      TEXT NOT NULL DEFAULT '',
    last_visited_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now')),
    UNIQUE (project_id, page_key)
);

CREATE TABLE web_review_item (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    revision_id        INTEGER NOT NULL REFERENCES web_review_revision (id) ON DELETE CASCADE,
    page_id            INTEGER NOT NULL REFERENCES web_review_page (id) ON DELETE CASCADE,
    n                  INTEGER NOT NULL,
    anchor_json        TEXT NOT NULL,
    comment            TEXT NOT NULL DEFAULT '',
    style_changes_json TEXT NOT NULL DEFAULT '[]',
    status             TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'SENT', 'DONE', 'DISMISSED')),
    screenshot_file    TEXT,
    created_at         TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    viewport_width     INTEGER,
    viewport_height    INTEGER,
    device_name        TEXT,
    device_category    TEXT CHECK (device_category IS NULL OR device_category IN ('MOBILE', 'TABLET', 'DESKTOP'))
);

CREATE INDEX idx_web_review_item_revision ON web_review_item (revision_id);
CREATE INDEX idx_web_review_item_page_status ON web_review_item (page_id, status);
CREATE UNIQUE INDEX idx_web_review_item_revision_number ON web_review_item (revision_id, n);
CREATE UNIQUE INDEX idx_web_review_one_pending_revision ON web_review_revision (project_id) WHERE status = 'PENDING';
CREATE INDEX idx_web_review_revision_project_status ON web_review_revision (project_id, status);

-- Code Mode

CREATE TABLE project_code_mode (
    project_id           INTEGER PRIMARY KEY REFERENCES project (id) ON DELETE CASCADE,
    enabled              INTEGER NOT NULL DEFAULT 0,
    servers              TEXT NOT NULL DEFAULT '[]',
    allow_all_tool_calls INTEGER NOT NULL DEFAULT 0,
    tool_call_mode       TEXT NOT NULL DEFAULT 'ASK',
    allowed_servers      TEXT NOT NULL DEFAULT '[]'
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
