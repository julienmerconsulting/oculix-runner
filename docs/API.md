# oculix-runner API

Base URL: `http://<host>:8765`. Every response is JSON except the script content, the streamed
log, the live view and artifact downloads. All examples below are real responses from a running
service; ids and timestamps are the ones it produced.

## Authentication

Every route except `GET /health` needs an API key, in the `X-Api-Key` header or, for routes a
browser opens directly, as `?key=` in the query. Keys carry scopes:

| Scope | Grants |
|---|---|
| `read` | every `GET` |
| `run` | `read` + scripts, suites, runs, abort |
| `admin` | `run` + projects, targets, keys, audit |

The first admin key is created at first start: `RUNNER_BOOTSTRAP_KEY` if set, otherwise generated
and printed once in the service log. Only the SHA-256 of a key is stored.

Errors are `{"error": "…"}` with the HTTP status:

| Status | When | Example |
|---|---|---|
| 400 | missing or invalid field | `{"error":"give either script_id or code"}` |
| 401 | no key or unknown key | `{"error":"X-Api-Key header required"}` |
| 403 | key lacks the scope | `{"error":"key 'ci-reader' lacks scope run"}` |
| 404 | unknown id | `{"error":"run not found"}` |
| 409 | conflict with the current state | `{"error":"run is already passed"}`, `{"error":"project code already exists: lab"}` |

Throughout: `K='X-Api-Key: orx_my_key'` and `B=http://localhost:8765`.

## Health and version

`GET /health` (no key): engine state, running run, queue length. `status` is `ok` once the
engine is ready; before that it is `starting`. Runs submitted while starting wait in the queue.

```
$ curl -s $B/health
{"status":"ok","engine":"ready","detail":"OculiX 4.0.0 ready in 13.3s","running_run_id":null,"queued":0}
```

`GET /version` (read): service version, OculiX version, path and SHA-256 of the jar, Java.

```
{"service":"0.1.0","oculix_version":"4.0.0","oculix_jar":"/opt/oculix/oculix.jar","jar_sha256":"a71de8a6…","java":"17.0.20","engine":"ready"}
```

## Keys

`POST /keys` (admin), body `{"name": "…", "scopes": "read"}`; `scopes` is a comma-separated
subset of `read,run,admin`. The clear key is returned once.

```
$ curl -s -X POST -H "$K" -H 'Content-Type: application/json' -d '{"name":"ci-reader","scopes":"read"}' $B/keys
{"id":2,"name":"ci-reader","scopes":"read","key":"orx_4dfa7340…","note":"shown once, store it now"}
```

`GET /keys` (admin) lists keys without their secret; `DELETE /keys/{id}` (admin) revokes one. A
key cannot revoke itself.

```
[{"id":1,"name":"bootstrap-admin","scopes":"admin","created_at":"2026-09-13T19:32:31.889Z","last_used_at":"2026-09-14T01:39:35.766Z","revoked_at":null},
 {"id":2,"name":"ci-reader","scopes":"read","created_at":"2026-09-14T01:39:35.738Z","last_used_at":null,"revoked_at":null}]
```

## Projects

A project is a namespace; its `code` (letters, digits, `_`, `-`, 32 max) names the artifacts
folder and can replace the id in `POST /runs`.

`POST /projects` (admin), body `{"code": "lab", "name": "Mainframe lab", "description": "…"}`.

```
{"id":1,"code":"lab","name":"Mainframe lab","description":null,"created_at":"2026-09-13T19:34:13.745Z"}
```

`GET /projects`, `GET /projects/{id}` (read).

## Targets

What a script drives. `kind` is `vnc` (default, needs `host`) or `local`. The VNC password is never
stored: `secret_ref` names an environment variable of the service; the script header resolves it.

`POST /projects/{id}/targets` (admin), body:

| Field | Type | Role |
|---|---|---|
| `name` | string, required | unique in the project |
| `kind` | `vnc` or `local` | default `vnc` |
| `host`, `port` | string, integer | VNC endpoint, `host` required for `vnc` |
| `display` | string | free, for `local` targets |
| `stage` | string | free label: `INT`, `PREPROD`… |
| `secret_ref` | string | name of the env var holding the password |

```
$ curl -s -X POST -H "$K" -H 'Content-Type: application/json' \
  -d '{"name":"tk5","host":"target-mainframe-kicks","port":5900,"stage":"INT"}' $B/projects/1/targets
{"id":1,"project_id":1,"name":"tk5","kind":"vnc","host":"target-mainframe-kicks","port":5900,"display":null,"stage":"INT","secret_ref":null,"status":"active","last_check_at":null,"last_check_result":null,"created_at":"2026-09-13T19:34:13.869Z","updated_at":"2026-09-13T19:34:13.869Z"}
```

`GET /projects/{id}/targets`, `GET /targets/{id}` (read); `PUT /targets/{id}` (admin) with any of
the fields above plus `status`.

`GET /targets/{id}/check` (run): a TCP connection to `host:port`, 3 s timeout, result stored on
the target.

```
{"target_id":1,"ok":true,"result":"tcp connect ok in 62 ms"}
{"target_id":1,"ok":false,"result":"unreachable: target-mainframe-kicks"}
```

## Scripts

A script is OculiX Jython text, versioned by content hash. Two ways in:

- `POST /projects/{id}/scripts` (run), JSON `{"name": "…", "content": "…", "external_ref": "…"}`.
- `POST /projects/{id}/scripts/raw?name=…&external_ref=…` (run), the `.py` file as the body.

```
$ curl -s -X POST -H "$K" --data-binary @tk5-type.py "$B/projects/1/scripts/raw?name=tk5-type"
{"id":1,"project_id":1,"name":"tk5-type","external_ref":null,"language":"jython","sha256":"780405b3…","version":1,"created_at":"2026-09-13T19:34:14.198Z","updated_at":"2026-09-13T19:34:14.198Z"}
```

`external_ref` is free: the id of the test case in your test management tool, for instance.

`GET /projects/{id}/scripts`, `GET /scripts/{id}` (read) return metadata; `GET /scripts/{id}/content`
returns the text itself (`text/x-python`). `PUT /scripts/{id}` (JSON) and `PUT /scripts/{id}/content`
(raw body) store a new version: `version` increments only when the content changed.

What the service adds in front of every script, and what a script can use, is described in the
README under "Writing a script": `RUN`, `PARAMS`, `TARGET`, `step()`.

## Runs

`POST /runs` (run). One run = one script executed once, in the warm JVM. One run executes at a
time; the others wait in creation order.

| Field | Type | Role |
|---|---|---|
| `project_id` or `project_code` | one required | the project |
| `script_id` or `code` | one required | a stored script, or inline Jython text for an ad-hoc run |
| `target_id` | integer | a target of the same project |
| `params` | object | passed to the script as `PARAMS` |
| `timeout_ms` | integer | abort after this delay; default `RUNNER_DEFAULT_TIMEOUT_MS` |
| `name` | string | defaults to the script name, or `ad-hoc` |

```
$ curl -s -X POST -H "$K" -H 'Content-Type: application/json' \
  -d '{"project_code":"lab","script_id":1,"target_id":1,"params":{"user":"HERC01"}}' $B/runs
{"id":1,…,"status":"queued","params_json":"{\"user\":\"HERC01\"}","trigger":"api","triggered_by":"bootstrap-admin",…}
```

`GET /runs/{id}` (read): the run row plus `line_count` and `steps`.

```
{"id":1,"project_id":1,"name":"tk5-type","script_id":1,"script_sha256":"780405b3…","target_id":1,"suite_run_id":null,"position":null,"continue_on_failure":0,
 "status":"passed","exit_code":0,"error_line":null,"error":null,"params_json":null,"trigger":"api","triggered_by":"bootstrap-admin",
 "oculix_version":"4.0.0","jar_sha256":"a71de8a6…","retry_of":null,"timeout_ms":0,
 "created_at":"2026-09-13T19:34:14.385Z","started_at":"2026-09-13T19:34:14.457Z","ended_at":"2026-09-13T19:34:31.966Z","duration_ms":17508,
 "line_count":15,"steps":[]}
```

Statuses: `queued`, `running`, `passed` (exit code 0), `failed` (exception or non-zero exit;
`error_line` is the line in your script, `error` a one-line summary), `timeout`, `aborted`,
`error` (the service itself failed), `skipped` (a previous run of the suite failed).

`oculix_version` and `jar_sha256` record which jar executed the run.

`GET /runs?project_id=&status=&limit=` (read) lists runs, newest first, 50 by default.

### Log

Every line the script prints, and every line OculiX prints while running it, is stored with a
sequence number and a millisecond timestamp.

`GET /runs/{id}/log?after=<seq>&wait=<seconds>` (read): lines after `seq`. With `wait`, the
call blocks up to that many seconds (60 max) until new lines exist or the run ends: poll with
`after=next`.

```
{"run_id":1,"status":"passed","finished":true,"next":15,"lines":[
 {"seq":1,"at":"2026-09-13T19:34:14.552Z","line":"[debug] Runner: runscript: running script: /workdir/runs/run_1.sikuli/run_1.py"},
 {"seq":2,"at":"2026-09-13T19:34:17.879Z","line":"[runner] Connexion a target-mainframe:5900 t+  0.00s"},
 …
 {"seq":15,"at":"2026-09-13T19:34:31.932Z","line":"[runner] Fin du script                t+ 14.05s"}]}
```

`GET /runs/{id}/log/stream` (read): the same as plain text, streamed until the run ends, closed
by a summary line. Made for a terminal:

```
$ curl -s -H "$K" $B/runs/1/log/stream
[debug] Runner: runscript: running script: /workdir/runs/run_1.sikuli/run_1.py
[runner] Connexion a target-mainframe:5900 t+  0.00s
…
--- run 1 passed (17508 ms)
```

### Steps

`GET /runs/{id}/steps` (read): the steps the script declared with `step(label, status, detail)`.
A `START` opens a step, a later `PASS` or `FAIL` with the same label closes it; a step still open
when the run ends becomes `INCOMPLETE`.

```
[{"id":1,"run_id":1,"step_order":1,"label":"logon","status":"PASS","detail":"warm jvm","started_at":"…","ended_at":"…","artifact_id":null}]
```

### Artifacts

`GET /runs/{id}/artifacts` (read) lists the files of a run; `GET /artifacts/{id}` downloads one
with its content type. Every run has `script.py`, the exact text that executed. A recorded run
also has `sidebyside.mp4`.

```
[{"id":4,"run_id":4,"kind":"script","filename":"script.py","content_type":"text/x-python","size":952,"sha256":"780405b3…","created_at":"2026-09-13T21:25:13.866Z"},
 {"id":5,"run_id":4,"kind":"video","filename":"sidebyside.mp4","content_type":"video/mp4","size":168718,"sha256":"41f0c0d5…","created_at":"2026-09-13T21:25:38.115Z"}]
```

### Abort

`POST /runs/{id}/abort` (run): a queued run is marked `aborted` at once; a running one is
interrupted through OculiX's abort and ends `aborted` within a second. A finished run answers 409.

```
{"run_id":3,"outcome":"abort requested"}
```

## Suites

A suite is an ordered list of items, each a script with an optional target, parameters and
`continue_on_failure`. Running a suite queues one run per item, in order.

`POST /projects/{id}/suites` (run), body `{"name": "…", "description": "…", "items": [...]}`;
`PUT /suites/{id}/items` (run) replaces the items; `GET /projects/{id}/suites`, `GET /suites/{id}`
(read).

```
$ curl -s -X POST -H "$K" -H 'Content-Type: application/json' \
  -d '{"name":"logon-and-check","items":[{"script_id":1,"target_id":1},{"script_id":2,"target_id":1}]}' $B/projects/1/suites
{"id":1,"project_id":1,"name":"logon-and-check","description":null,"status":"active","created_at":"…","updated_at":"…",
 "items":[{"id":1,"suite_id":1,"position":1,"script_id":1,"target_id":1,"params_json":null,"continue_on_failure":0,"script_name":"tk5-type","target_name":"tk5"},
          {"id":2,"suite_id":1,"position":2,"script_id":2,"target_id":1,"params_json":null,"continue_on_failure":0,"script_name":"oculix-check","target_name":"tk5"}]}
```

`POST /suites/{id}/run` (run), optional body `{"branch": "…", "commit_sha": "…", "retry_of": <id>}`,
returns the suite run with its runs. When a run does not pass and its item has no
`continue_on_failure`, the remaining runs are marked `skipped`.

```
{"id":3,"project_id":1,"suite_id":3,"status":"passed","trigger":"api","commit_sha":null,"branch":"lab","retry_of":null,"triggered_by":"bootstrap-admin",
 "created_at":"2026-09-13T18:23:01.952Z","started_at":"2026-09-13T18:23:02.017Z","ended_at":"2026-09-13T18:23:24.731Z",
 "runs":[{"id":11,"position":1,"name":"tk5-logon #1 tk5-type","script_id":3,"target_id":1,"status":"passed","exit_code":0,"error":null,"started_at":"…","ended_at":"…","duration_ms":14371},
         {"id":12,"position":2,"name":"tk5-logon #2 oculix-check","script_id":4,"target_id":1,"status":"passed","exit_code":0,"error":null,"started_at":"…","ended_at":"…","duration_ms":8319}]}
```

Suite run statuses: `queued`, `running`, `passed`, `failed`, `aborted`.

`GET /suite-runs/{id}`, `GET /projects/{id}/suite-runs` (read); `POST /suite-runs/{id}/abort`
(run) aborts the queued runs and the running one.

## Audit and engine events

`GET /audit?limit=` (admin): who did what, newest first. `actor` is the key name.

```
[{"id":9,"at":"2026-09-14T01:39:35.699Z","actor":"bootstrap-admin","action":"suite.create","entity":"suite","entity_id":1,"detail":"logon-and-check"}]
```

`GET /engine/events?limit=` (read): the engine's own life: start, natives, runners, ready,
recording events.

```
[{"id":18,"at":"2026-09-14T01:38:42.736Z","kind":"engine.ready","detail":"OculiX 4.0.0 ready in 13.3s"},
 {"id":17,"at":"2026-09-14T01:38:36.991Z","kind":"engine.runners","detail":"7.5s"}]
```

## Recording (experimental)

With `RUNNER_RECORD=1` on the service, or `"params": {"record": true}` on a run with a VNC target,
the service records the target's screen during the run and assembles `sidebyside.mp4`, log on the
left, screen on the right, listed in the run's artifacts. While the run executes,
`GET /runs/{id}/live?key=<key>` streams the same picture as MJPEG; open it in a browser. 404 when
nothing is recording.

## A complete session

```
K='X-Api-Key: orx_my_key'; B=http://localhost:8765
curl -s $B/health                                                                   # wait for "ready"
curl -s -X POST -H "$K" -H 'Content-Type: application/json' -d '{"code":"pos","name":"Point of sale"}' $B/projects
curl -s -X POST -H "$K" -H 'Content-Type: application/json' -d '{"name":"till-12","host":"10.0.4.12","port":5900,"secret_ref":"TILL_VNC_PASSWORD"}' $B/projects/1/targets
curl -s -H "$K" $B/targets/1/check
curl -s -X POST -H "$K" --data-binary @open-till.py "$B/projects/1/scripts/raw?name=open-till&external_ref=TC-118"
curl -s -X POST -H "$K" -H 'Content-Type: application/json' -d '{"project_code":"pos","script_id":1,"target_id":1,"timeout_ms":120000}' $B/runs
curl -s -H "$K" $B/runs/1/log/stream
curl -s -H "$K" $B/runs/1 ; curl -s -H "$K" $B/runs/1/steps ; curl -s -H "$K" $B/runs/1/artifacts
```
