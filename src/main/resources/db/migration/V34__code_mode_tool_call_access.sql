ALTER TABLE project_code_mode ADD COLUMN tool_call_mode TEXT NOT NULL DEFAULT 'ASK';
ALTER TABLE project_code_mode ADD COLUMN allowed_servers TEXT NOT NULL DEFAULT '[]';
UPDATE project_code_mode SET tool_call_mode = 'ALL' WHERE allow_all_tool_calls = 1;
