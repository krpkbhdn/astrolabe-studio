ALTER TABLE project ADD COLUMN trust_level TEXT;

CREATE TABLE trust_settings (
    id            INTEGER PRIMARY KEY CHECK (id = 1),
    default_level TEXT NOT NULL
);

INSERT INTO trust_settings (id, default_level) VALUES (1, 'TRUSTED');
