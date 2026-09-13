-- oculix-runner-service schema, first lot.
-- Applied at startup; every statement is idempotent (IF NOT EXISTS).
-- Timestamps are ISO-8601 UTC strings. JSON columns hold serialized JSON text.

CREATE TABLE IF NOT EXISTS projects (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  code        TEXT NOT NULL UNIQUE,
  name        TEXT NOT NULL,
  description TEXT,
  created_at  TEXT NOT NULL
);

-- What a script drives. The VNC password never lives here: secret_ref is the
-- name of an environment variable of the runner process.
CREATE TABLE IF NOT EXISTS targets (
  id                INTEGER PRIMARY KEY AUTOINCREMENT,
  project_id        INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  name              TEXT NOT NULL,
  kind              TEXT NOT NULL DEFAULT 'vnc' CHECK (kind IN ('vnc', 'local')),
  host              TEXT,
  port              INTEGER,
  display           TEXT,
  stage             TEXT,
  secret_ref        TEXT,
  status            TEXT NOT NULL DEFAULT 'active',
  last_check_at     TEXT,
  last_check_result TEXT,
  created_at        TEXT NOT NULL,
  updated_at        TEXT NOT NULL,
  UNIQUE (project_id, name)
);

CREATE TABLE IF NOT EXISTS scripts (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  project_id   INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  name         TEXT NOT NULL,
  external_ref TEXT,
  language     TEXT NOT NULL DEFAULT 'jython',
  content      TEXT NOT NULL,
  sha256       TEXT NOT NULL,
  version      INTEGER NOT NULL DEFAULT 1,
  created_at   TEXT NOT NULL,
  updated_at   TEXT NOT NULL,
  UNIQUE (project_id, name)
);

CREATE TABLE IF NOT EXISTS suites (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  project_id  INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  name        TEXT NOT NULL,
  description TEXT,
  status      TEXT NOT NULL DEFAULT 'active',
  created_at  TEXT NOT NULL,
  updated_at  TEXT NOT NULL,
  UNIQUE (project_id, name)
);

CREATE TABLE IF NOT EXISTS suite_items (
  id                  INTEGER PRIMARY KEY AUTOINCREMENT,
  suite_id            INTEGER NOT NULL REFERENCES suites(id) ON DELETE CASCADE,
  position            INTEGER NOT NULL,
  script_id           INTEGER NOT NULL REFERENCES scripts(id) ON DELETE CASCADE,
  target_id           INTEGER REFERENCES targets(id) ON DELETE SET NULL,
  params_json         TEXT,
  continue_on_failure INTEGER NOT NULL DEFAULT 0,
  UNIQUE (suite_id, position)
);

CREATE TABLE IF NOT EXISTS suite_runs (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  project_id   INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  suite_id     INTEGER NOT NULL REFERENCES suites(id) ON DELETE CASCADE,
  status       TEXT NOT NULL DEFAULT 'queued',
  trigger      TEXT NOT NULL DEFAULT 'api',
  commit_sha   TEXT,
  branch       TEXT,
  retry_of     INTEGER REFERENCES suite_runs(id),
  triggered_by TEXT,
  created_at   TEXT NOT NULL,
  started_at   TEXT,
  ended_at     TEXT
);

-- One run = one script execution in the warm JVM. Exactly one is 'running' at
-- any time; the others wait as 'queued', ordered by id.
CREATE TABLE IF NOT EXISTS runs (
  id                  INTEGER PRIMARY KEY AUTOINCREMENT,
  project_id          INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  name                TEXT,
  script_id           INTEGER REFERENCES scripts(id) ON DELETE SET NULL,
  script_sha256       TEXT NOT NULL,
  target_id           INTEGER REFERENCES targets(id) ON DELETE SET NULL,
  suite_run_id        INTEGER REFERENCES suite_runs(id) ON DELETE CASCADE,
  position            INTEGER,
  continue_on_failure INTEGER NOT NULL DEFAULT 0,
  -- 'new' = inserted, script artifact not written yet; the worker ignores it.
  status              TEXT NOT NULL DEFAULT 'queued'
                      CHECK (status IN ('new','queued','running','passed','failed','aborted','timeout','error','skipped')),
  exit_code           INTEGER,
  error_line          INTEGER,
  error               TEXT,
  params_json         TEXT,
  trigger             TEXT NOT NULL DEFAULT 'api',
  triggered_by        TEXT,
  oculix_version      TEXT,
  jar_sha256          TEXT,
  retry_of            INTEGER REFERENCES runs(id),
  timeout_ms          INTEGER NOT NULL DEFAULT 0,
  created_at          TEXT NOT NULL,
  started_at          TEXT,
  ended_at            TEXT,
  duration_ms         INTEGER
);
CREATE INDEX IF NOT EXISTS runs_status ON runs(status, id);
CREATE INDEX IF NOT EXISTS runs_suite_run ON runs(suite_run_id);

CREATE TABLE IF NOT EXISTS run_lines (
  id     INTEGER PRIMARY KEY AUTOINCREMENT,
  run_id INTEGER NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
  seq    INTEGER NOT NULL,
  at     TEXT NOT NULL,
  line   TEXT NOT NULL,
  UNIQUE (run_id, seq)
);

-- Declared by the script itself through step(label, status, detail), which
-- the injected header turns into a marked stdout line.
CREATE TABLE IF NOT EXISTS run_steps (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  run_id      INTEGER NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
  step_order  INTEGER NOT NULL,
  label       TEXT NOT NULL,
  status      TEXT NOT NULL,
  detail      TEXT,
  started_at  TEXT NOT NULL,
  ended_at    TEXT,
  artifact_id INTEGER REFERENCES artifacts(id) ON DELETE SET NULL
);
CREATE INDEX IF NOT EXISTS run_steps_run ON run_steps(run_id, step_order);

-- Files on disk under <data>/artifacts/<project code>/<run id>/ ; the base
-- keeps the pointer, size and hash, never the bytes.
CREATE TABLE IF NOT EXISTS artifacts (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  run_id       INTEGER NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
  kind         TEXT NOT NULL,
  filename     TEXT NOT NULL,
  path         TEXT NOT NULL,
  content_type TEXT NOT NULL,
  size         INTEGER NOT NULL,
  sha256       TEXT NOT NULL,
  created_at   TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS engine_events (
  id     INTEGER PRIMARY KEY AUTOINCREMENT,
  at     TEXT NOT NULL,
  kind   TEXT NOT NULL,
  detail TEXT
);

-- Keys, not users. Only the SHA-256 of the key is stored.
CREATE TABLE IF NOT EXISTS api_keys (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  name         TEXT NOT NULL UNIQUE,
  key_hash     TEXT NOT NULL UNIQUE,
  scopes       TEXT NOT NULL,
  created_at   TEXT NOT NULL,
  last_used_at TEXT,
  revoked_at   TEXT
);

CREATE TABLE IF NOT EXISTS audit_log (
  id        INTEGER PRIMARY KEY AUTOINCREMENT,
  at        TEXT NOT NULL,
  actor     TEXT NOT NULL,
  action    TEXT NOT NULL,
  entity    TEXT,
  entity_id INTEGER,
  detail    TEXT
);
