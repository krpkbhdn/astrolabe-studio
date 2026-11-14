-- SQLite cannot alter a CHECK constraint, so the table is rebuilt with CODE_MODE allowed.
CREATE TABLE project_preset_component_new (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    preset_id INTEGER NOT NULL REFERENCES project_preset (id) ON DELETE CASCADE,
    position  INTEGER NOT NULL,
    kind      TEXT    NOT NULL CHECK (kind IN ('MARKETPLACE', 'PLUGIN', 'SKILL', 'AGENT', 'MCP_SERVER', 'INSTRUCTION', 'SETTINGS', 'FILE', 'CODE_MODE')),
    name      TEXT    NOT NULL,
    payload   TEXT    NOT NULL,
    UNIQUE (preset_id, kind, name)
);

INSERT INTO project_preset_component_new (id, preset_id, position, kind, name, payload)
SELECT id, preset_id, position, kind, name, payload FROM project_preset_component;

DROP TABLE project_preset_component;
ALTER TABLE project_preset_component_new RENAME TO project_preset_component;
