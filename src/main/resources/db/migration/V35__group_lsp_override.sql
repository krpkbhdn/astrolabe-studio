CREATE TABLE group_lsp_override (
    group_id INTEGER PRIMARY KEY REFERENCES project(id) ON DELETE CASCADE
);
