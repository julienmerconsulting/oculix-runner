package org.oculix.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Every route of the service. Scopes: null = public, read < run < admin. */
public final class Api {

  public static final String VERSION = "0.1.0";

  private final Config cfg;
  private final Db db;
  private final Engine engine;
  private final Auth auth;

  public Api(Config cfg, Db db, Engine engine, Auth auth) {
    this.cfg = cfg;
    this.db = db;
    this.engine = engine;
    this.auth = auth;
  }

  public void mount(Http http) {
    http.route("GET", "/health", null, this::health);
    http.route("GET", "/version", "read", this::version);
    http.route("GET", "/engine/events", "read", r -> db.query("SELECT * FROM engine_events ORDER BY id DESC LIMIT ?", r.queryInt("limit", 50)));

    http.route("POST", "/projects", "admin", this::createProject);
    http.route("GET", "/projects", "read", r -> db.query("SELECT * FROM projects ORDER BY id"));
    http.route("GET", "/projects/{id}", "read", r -> must(db.one("SELECT * FROM projects WHERE id=?", r.id("id")), "project"));

    http.route("POST", "/projects/{id}/targets", "admin", this::createTarget);
    http.route("GET", "/projects/{id}/targets", "read", r -> db.query("SELECT * FROM targets WHERE project_id=? ORDER BY id", r.id("id")));
    http.route("GET", "/targets/{id}", "read", r -> must(db.one("SELECT * FROM targets WHERE id=?", r.id("id")), "target"));
    http.route("PUT", "/targets/{id}", "admin", this::updateTarget);
    http.route("GET", "/targets/{id}/check", "run", this::checkTarget);

    http.route("POST", "/projects/{id}/scripts", "run", this::createScript);
    http.route("GET", "/projects/{id}/scripts", "read", r -> db.query("SELECT id, project_id, name, external_ref, language, sha256, version, created_at, updated_at FROM scripts WHERE project_id=? ORDER BY id", r.id("id")));
    http.route("GET", "/scripts/{id}", "read", r -> must(db.one("SELECT id, project_id, name, external_ref, language, sha256, version, created_at, updated_at FROM scripts WHERE id=?", r.id("id")), "script"));
    http.route("GET", "/scripts/{id}/content", "read", this::scriptContent);
    http.route("PUT", "/scripts/{id}", "run", this::updateScript);
    // Raw variants: the .py itself as request body, nothing to wrap.
    //   curl -X POST --data-binary @tk5-type.py '.../projects/1/scripts/raw?name=tk5-type&external_ref=TK5-LOGON'
    //   curl -X PUT  --data-binary @tk5-type.py '.../scripts/3/content'
    http.route("POST", "/projects/{id}/scripts/raw", "run", this::createScriptRaw);
    http.route("PUT", "/scripts/{id}/content", "run", this::updateScriptRaw);

    http.route("POST", "/projects/{id}/suites", "run", this::createSuite);
    http.route("GET", "/projects/{id}/suites", "read", r -> db.query("SELECT * FROM suites WHERE project_id=? ORDER BY id", r.id("id")));
    http.route("GET", "/suites/{id}", "read", this::getSuite);
    http.route("PUT", "/suites/{id}/items", "run", this::setSuiteItems);
    http.route("POST", "/suites/{id}/run", "run", this::runSuite);

    http.route("GET", "/projects/{id}/suite-runs", "read", r -> db.query("SELECT * FROM suite_runs WHERE project_id=? ORDER BY id DESC LIMIT ?", r.id("id"), r.queryInt("limit", 50)));
    http.route("GET", "/suite-runs/{id}", "read", this::getSuiteRun);
    http.route("POST", "/suite-runs/{id}/abort", "run", this::abortSuiteRun);

    http.route("POST", "/runs", "run", this::createRun);
    http.route("GET", "/runs", "read", this::listRuns);
    http.route("GET", "/runs/{id}", "read", this::getRun);
    http.route("GET", "/runs/{id}/log", "read", this::runLog);
    http.route("GET", "/runs/{id}/log/stream", "read", this::runLogStream);
    http.route("GET", "/runs/{id}/steps", "read", r -> db.query("SELECT * FROM run_steps WHERE run_id=? ORDER BY step_order", r.id("id")));
    http.route("GET", "/runs/{id}/artifacts", "read", r -> db.query("SELECT id, run_id, kind, filename, content_type, size, sha256, created_at FROM artifacts WHERE run_id=? ORDER BY id", r.id("id")));
    http.route("POST", "/runs/{id}/abort", "run", this::abortRun);
    http.route("GET", "/artifacts/{id}", "read", this::downloadArtifact);

    http.route("POST", "/keys", "admin", this::createKey);
    http.route("GET", "/keys", "admin", r -> db.query("SELECT id, name, scopes, created_at, last_used_at, revoked_at FROM api_keys ORDER BY id"));
    http.route("DELETE", "/keys/{id}", "admin", this::revokeKey);
    http.route("GET", "/audit", "admin", r -> db.query("SELECT * FROM audit_log ORDER BY id DESC LIMIT ?", r.queryInt("limit", 100)));
  }

  // ---------------------------------------------------------------- health / version

  private Object health(Http.Req r) throws SQLException {
    return Json.map(
        "status", engine.state() == Engine.State.READY ? "ok" : engine.state().name().toLowerCase(),
        "engine", engine.state().name().toLowerCase(),
        "detail", engine.stateDetail(),
        "running_run_id", engine.currentRunId(),
        "queued", db.count("SELECT COUNT(*) FROM runs WHERE status='queued'"));
  }

  private Object version(Http.Req r) {
    return Json.map(
        "service", VERSION,
        "oculix_version", engine.oculixVersion(),
        "oculix_jar", cfg.oculixJar.toString(),
        "jar_sha256", engine.jarSha256(),
        "java", System.getProperty("java.version"),
        "engine", engine.state().name().toLowerCase());
  }

  // ---------------------------------------------------------------- projects

  private Object createProject(Http.Req r) throws Exception {
    JsonNode b = r.body();
    String code = Json.req(b, "code");
    if (!code.matches("[A-Za-z0-9_-]{1,32}")) throw new Http.ApiError(400, "code: letters, digits, _ and - only (max 32)");
    if (db.one("SELECT id FROM projects WHERE code=?", code) != null) throw new Http.ApiError(409, "project code already exists: " + code);
    long id = db.insert("INSERT INTO projects(code, name, description, created_at) VALUES (?,?,?,?)",
        code, Json.req(b, "name"), Json.str(b, "description"), Db.now());
    audit(r, "project.create", "project", id, code);
    return db.one("SELECT * FROM projects WHERE id=?", id);
  }

  // ---------------------------------------------------------------- targets

  private Object createTarget(Http.Req r) throws Exception {
    long projectId = r.id("id");
    must(db.one("SELECT id FROM projects WHERE id=?", projectId), "project");
    JsonNode b = r.body();
    String kind = b.hasNonNull("kind") ? Json.req(b, "kind") : "vnc";
    if (!kind.equals("vnc") && !kind.equals("local")) throw new Http.ApiError(400, "kind must be vnc or local");
    if (kind.equals("vnc") && Json.str(b, "host") == null) throw new Http.ApiError(400, "a vnc target needs a host");
    String now = Db.now();
    long id;
    try {
      id = db.insert("INSERT INTO targets(project_id, name, kind, host, port, display, stage, secret_ref, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
          projectId, Json.req(b, "name"), kind, Json.str(b, "host"), Json.lng(b, "port"), Json.str(b, "display"),
          Json.str(b, "stage"), Json.str(b, "secret_ref"), now, now);
    } catch (SQLException e) {
      throw conflict(e, "target name already exists in this project");
    }
    audit(r, "target.create", "target", id, Json.req(b, "name"));
    return db.one("SELECT * FROM targets WHERE id=?", id);
  }

  private Object updateTarget(Http.Req r) throws Exception {
    long id = r.id("id");
    Map<String, Object> t = must(db.one("SELECT * FROM targets WHERE id=?", id), "target");
    JsonNode b = r.body();
    db.update("UPDATE targets SET name=?, host=?, port=?, display=?, stage=?, secret_ref=?, status=?, updated_at=? WHERE id=?",
        pick(b, "name", t), pick(b, "host", t), b.hasNonNull("port") ? Json.lng(b, "port") : t.get("port"),
        pick(b, "display", t), pick(b, "stage", t), pick(b, "secret_ref", t), pick(b, "status", t), Db.now(), id);
    audit(r, "target.update", "target", id, null);
    return db.one("SELECT * FROM targets WHERE id=?", id);
  }

  private Object checkTarget(Http.Req r) throws Exception {
    long id = r.id("id");
    Map<String, Object> t = must(db.one("SELECT * FROM targets WHERE id=?", id), "target");
    String result;
    boolean ok;
    if (!"vnc".equals(t.get("kind"))) {
      ok = true;
      result = "local target, nothing to reach";
    } else {
      long t0 = System.nanoTime();
      try (Socket s = new Socket()) {
        s.connect(new InetSocketAddress(String.valueOf(t.get("host")), ((Number) t.get("port")).intValue()), 3000);
        ok = true;
        result = String.format("tcp connect ok in %d ms", (System.nanoTime() - t0) / 1_000_000);
      } catch (IOException e) {
        ok = false;
        result = "unreachable: " + e.getMessage();
      }
    }
    db.update("UPDATE targets SET last_check_at=?, last_check_result=? WHERE id=?", Db.now(), result, id);
    return Json.map("target_id", id, "ok", ok, "result", result);
  }

  // ---------------------------------------------------------------- scripts

  private Object createScript(Http.Req r) throws Exception {
    long projectId = r.id("id");
    must(db.one("SELECT id FROM projects WHERE id=?", projectId), "project");
    JsonNode b = r.body();
    String content = Json.req(b, "content");
    String now = Db.now();
    long id;
    try {
      id = db.insert("INSERT INTO scripts(project_id, name, external_ref, language, content, sha256, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)",
          projectId, Json.req(b, "name"), Json.str(b, "external_ref"), "jython", content, Auth.hash(content), now, now);
    } catch (SQLException e) {
      throw conflict(e, "script name already exists in this project");
    }
    audit(r, "script.create", "script", id, Json.req(b, "name"));
    return db.one("SELECT id, project_id, name, external_ref, language, sha256, version, created_at, updated_at FROM scripts WHERE id=?", id);
  }

  private Object updateScript(Http.Req r) throws Exception {
    long id = r.id("id");
    Map<String, Object> s = must(db.one("SELECT * FROM scripts WHERE id=?", id), "script");
    JsonNode b = r.body();
    String content = b.hasNonNull("content") ? b.get("content").asText() : String.valueOf(s.get("content"));
    boolean changed = !content.equals(s.get("content"));
    db.update("UPDATE scripts SET name=?, external_ref=?, content=?, sha256=?, version=version+?, updated_at=? WHERE id=?",
        pick(b, "name", s), pick(b, "external_ref", s), content, Auth.hash(content), changed ? 1 : 0, Db.now(), id);
    audit(r, "script.update", "script", id, changed ? "new version" : "metadata only");
    return db.one("SELECT id, project_id, name, external_ref, language, sha256, version, created_at, updated_at FROM scripts WHERE id=?", id);
  }

  private Object createScriptRaw(Http.Req r) throws Exception {
    long projectId = r.id("id");
    must(db.one("SELECT id FROM projects WHERE id=?", projectId), "project");
    String name = r.query.get("name");
    if (name == null || name.isBlank()) throw new Http.ApiError(400, "query parameter name required");
    String content = rawBody(r);
    String now = Db.now();
    long id;
    try {
      id = db.insert("INSERT INTO scripts(project_id, name, external_ref, language, content, sha256, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)",
          projectId, name.trim(), r.query.get("external_ref"), "jython", content, Auth.hash(content), now, now);
    } catch (SQLException e) {
      throw conflict(e, "script name already exists in this project");
    }
    audit(r, "script.create", "script", id, name.trim());
    return db.one("SELECT id, project_id, name, external_ref, language, sha256, version, created_at, updated_at FROM scripts WHERE id=?", id);
  }

  private Object updateScriptRaw(Http.Req r) throws Exception {
    long id = r.id("id");
    Map<String, Object> s = must(db.one("SELECT * FROM scripts WHERE id=?", id), "script");
    String content = rawBody(r);
    boolean changed = !content.equals(s.get("content"));
    db.update("UPDATE scripts SET content=?, sha256=?, version=version+?, updated_at=? WHERE id=?",
        content, Auth.hash(content), changed ? 1 : 0, Db.now(), id);
    audit(r, "script.update", "script", id, changed ? "new version (raw)" : "unchanged (raw)");
    return db.one("SELECT id, project_id, name, external_ref, language, sha256, version, created_at, updated_at FROM scripts WHERE id=?", id);
  }

  private static String rawBody(Http.Req r) throws IOException {
    String content = new String(r.exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    if (content.isBlank()) throw new Http.ApiError(400, "empty body: send the script itself");
    return content;
  }

  private Object scriptContent(Http.Req r) throws Exception {
    Map<String, Object> s = must(db.one("SELECT content FROM scripts WHERE id=?", r.id("id")), "script");
    return new Http.Raw(200, "text/x-python", String.valueOf(s.get("content")).getBytes(StandardCharsets.UTF_8));
  }

  // ---------------------------------------------------------------- suites

  private Object createSuite(Http.Req r) throws Exception {
    long projectId = r.id("id");
    must(db.one("SELECT id FROM projects WHERE id=?", projectId), "project");
    JsonNode b = r.body();
    String now = Db.now();
    long id;
    try {
      id = db.insert("INSERT INTO suites(project_id, name, description, created_at, updated_at) VALUES (?,?,?,?,?)",
          projectId, Json.req(b, "name"), Json.str(b, "description"), now, now);
    } catch (SQLException e) {
      throw conflict(e, "suite name already exists in this project");
    }
    if (b.has("items")) setItems(id, projectId, b.get("items"));
    audit(r, "suite.create", "suite", id, Json.req(b, "name"));
    return suiteView(id);
  }

  private Object getSuite(Http.Req r) throws Exception {
    must(db.one("SELECT id FROM suites WHERE id=?", r.id("id")), "suite");
    return suiteView(r.id("id"));
  }

  private Map<String, Object> suiteView(long id) throws SQLException {
    Map<String, Object> suite = db.one("SELECT * FROM suites WHERE id=?", id);
    suite.put("items", db.query("SELECT i.*, s.name AS script_name, t.name AS target_name FROM suite_items i JOIN scripts s ON s.id=i.script_id LEFT JOIN targets t ON t.id=i.target_id WHERE i.suite_id=? ORDER BY i.position", id));
    return suite;
  }

  private Object setSuiteItems(Http.Req r) throws Exception {
    long id = r.id("id");
    Map<String, Object> suite = must(db.one("SELECT * FROM suites WHERE id=?", id), "suite");
    JsonNode items = r.body().get("items");
    if (items == null || !items.isArray()) throw new Http.ApiError(400, "body must be {\"items\": [...]}");
    setItems(id, ((Number) suite.get("project_id")).longValue(), items);
    db.update("UPDATE suites SET updated_at=? WHERE id=?", Db.now(), id);
    audit(r, "suite.items", "suite", id, items.size() + " item(s)");
    return suiteView(id);
  }

  private void setItems(long suiteId, long projectId, JsonNode items) throws SQLException {
    if (!items.isArray()) throw new Http.ApiError(400, "items must be an array");
    db.update("DELETE FROM suite_items WHERE suite_id=?", suiteId);
    int position = 0;
    for (JsonNode it : items) {
      Long scriptId = Json.lng(it, "script_id");
      if (scriptId == null) throw new Http.ApiError(400, "each item needs a script_id");
      if (db.one("SELECT id FROM scripts WHERE id=? AND project_id=?", scriptId, projectId) == null) throw new Http.ApiError(400, "script " + scriptId + " is not in this project");
      Long targetId = Json.lng(it, "target_id");
      if (targetId != null && db.one("SELECT id FROM targets WHERE id=? AND project_id=?", targetId, projectId) == null) throw new Http.ApiError(400, "target " + targetId + " is not in this project");
      db.insert("INSERT INTO suite_items(suite_id, position, script_id, target_id, params_json, continue_on_failure) VALUES (?,?,?,?,?,?)",
          suiteId, ++position, scriptId, targetId, Json.objectText(it, "params"), Json.bool(it, "continue_on_failure", false) ? 1 : 0);
    }
  }

  private Object runSuite(Http.Req r) throws Exception {
    long suiteId = r.id("id");
    Map<String, Object> suite = must(db.one("SELECT * FROM suites WHERE id=?", suiteId), "suite");
    List<Map<String, Object>> items = db.query("SELECT * FROM suite_items WHERE suite_id=? ORDER BY position", suiteId);
    if (items.isEmpty()) throw new Http.ApiError(400, "suite has no items");
    JsonNode b = r.body();
    long projectId = ((Number) suite.get("project_id")).longValue();
    long suiteRunId = db.insert("INSERT INTO suite_runs(project_id, suite_id, status, trigger, commit_sha, branch, retry_of, triggered_by, created_at) VALUES (?,?,?,?,?,?,?,?,?)",
        projectId, suiteId, "queued", "api", Json.str(b, "commit_sha"), Json.str(b, "branch"), Json.lng(b, "retry_of"), r.actor(), Db.now());
    List<Long> runIds = new ArrayList<>();
    for (Map<String, Object> it : items) {
      Map<String, Object> script = db.one("SELECT * FROM scripts WHERE id=?", it.get("script_id"));
      runIds.add(newRun(projectId, script, null, String.valueOf(suite.get("name")) + " #" + it.get("position") + " " + script.get("name"),
          it.get("target_id") == null ? null : ((Number) it.get("target_id")).longValue(),
          it.get("params_json") == null ? null : String.valueOf(it.get("params_json")),
          cfg.defaultTimeoutMs, suiteRunId, ((Number) it.get("position")).intValue(),
          ((Number) it.get("continue_on_failure")).intValue() != 0, "suite", r.actor()));
    }
    audit(r, "suite.run", "suite_run", suiteRunId, runIds.size() + " run(s) queued");
    engine.poke();
    return suiteRunView(suiteRunId);
  }

  private Object getSuiteRun(Http.Req r) throws Exception {
    must(db.one("SELECT id FROM suite_runs WHERE id=?", r.id("id")), "suite run");
    return suiteRunView(r.id("id"));
  }

  private Map<String, Object> suiteRunView(long id) throws SQLException {
    Map<String, Object> sr = db.one("SELECT * FROM suite_runs WHERE id=?", id);
    sr.put("runs", db.query("SELECT id, position, name, script_id, target_id, status, exit_code, error, started_at, ended_at, duration_ms FROM runs WHERE suite_run_id=? ORDER BY position", id));
    return sr;
  }

  private Object abortSuiteRun(Http.Req r) throws Exception {
    long id = r.id("id");
    must(db.one("SELECT id FROM suite_runs WHERE id=?", id), "suite run");
    int queued = db.update("UPDATE runs SET status='aborted', error='aborted before start', ended_at=? WHERE suite_run_id=? AND status='queued'", Db.now(), id);
    Map<String, Object> running = db.one("SELECT id FROM runs WHERE suite_run_id=? AND status='running'", id);
    boolean abortedRunning = running != null && engine.abort(((Number) running.get("id")).longValue());
    if (running == null) {
      db.update("UPDATE suite_runs SET status='aborted', ended_at=? WHERE id=? AND status IN ('queued','running')", Db.now(), id);
    }
    audit(r, "suite_run.abort", "suite_run", id, queued + " queued aborted, running aborted: " + abortedRunning);
    return suiteRunView(id);
  }

  // ---------------------------------------------------------------- runs

  private Object createRun(Http.Req r) throws Exception {
    JsonNode b = r.body();
    Map<String, Object> project = resolveProject(b);
    long projectId = ((Number) project.get("id")).longValue();
    Long scriptId = Json.lng(b, "script_id");
    String code = Json.str(b, "code");
    if ((scriptId == null) == (code == null)) throw new Http.ApiError(400, "give either script_id or code");
    Map<String, Object> script = null;
    if (scriptId != null) {
      script = db.one("SELECT * FROM scripts WHERE id=? AND project_id=?", scriptId, projectId);
      if (script == null) throw new Http.ApiError(404, "script " + scriptId + " not found in project " + project.get("code"));
    }
    Long targetId = Json.lng(b, "target_id");
    if (targetId != null && db.one("SELECT id FROM targets WHERE id=? AND project_id=?", targetId, projectId) == null) throw new Http.ApiError(404, "target " + targetId + " not found in project " + project.get("code"));
    Long timeout = Json.lng(b, "timeout_ms");
    String name = Json.str(b, "name");
    if (name == null) name = script != null ? String.valueOf(script.get("name")) : "ad-hoc";
    long id = newRun(projectId, script, code, name, targetId, Json.objectText(b, "params"),
        timeout == null ? cfg.defaultTimeoutMs : timeout, null, null, false, "api", r.actor());
    audit(r, "run.create", "run", id, name);
    engine.poke();
    return db.one("SELECT * FROM runs WHERE id=?", id);
  }

  /** Inserts the run and its script artifact, then makes it visible to the worker. */
  private long newRun(long projectId, Map<String, Object> script, String inlineCode, String name, Long targetId, String paramsJson,
                      long timeoutMs, Long suiteRunId, Integer position, boolean continueOnFailure, String trigger, String actor) throws Exception {
    String content = script != null ? String.valueOf(script.get("content")) : inlineCode;
    String now = Db.now();
    long id = db.insert("INSERT INTO runs(project_id, name, script_id, script_sha256, target_id, suite_run_id, position, continue_on_failure, status, params_json, trigger, triggered_by, timeout_ms, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        projectId, name, script == null ? null : script.get("id"), Auth.hash(content), targetId, suiteRunId, position,
        continueOnFailure ? 1 : 0, "new", paramsJson, trigger, actor, timeoutMs, now);
    Map<String, Object> project = db.one("SELECT code FROM projects WHERE id=?", projectId);
    Path dir = cfg.artifactsDir().resolve(String.valueOf(project.get("code"))).resolve(String.valueOf(id));
    Files.createDirectories(dir);
    Path scriptFile = dir.resolve("script.py");
    Files.writeString(scriptFile, content);
    db.insert("INSERT INTO artifacts(run_id, kind, filename, path, content_type, size, sha256, created_at) VALUES (?,?,?,?,?,?,?,?)",
        id, "script", "script.py", scriptFile.toString(), "text/x-python", Files.size(scriptFile), Auth.hash(content), now);
    db.update("UPDATE runs SET status='queued' WHERE id=?", id);
    return id;
  }

  private Map<String, Object> resolveProject(JsonNode b) throws SQLException {
    Long id = Json.lng(b, "project_id");
    String code = Json.str(b, "project_code");
    if (id == null && code == null) throw new Http.ApiError(400, "project_id or project_code required");
    Map<String, Object> p = id != null ? db.one("SELECT * FROM projects WHERE id=?", id) : db.one("SELECT * FROM projects WHERE code=?", code);
    if (p == null) throw new Http.ApiError(404, "project not found");
    return p;
  }

  private Object listRuns(Http.Req r) throws Exception {
    StringBuilder sql = new StringBuilder("SELECT id, project_id, name, script_id, target_id, suite_run_id, position, status, exit_code, error, trigger, triggered_by, created_at, started_at, ended_at, duration_ms FROM runs WHERE 1=1");
    List<Object> args = new ArrayList<>();
    if (r.query.containsKey("project_id")) {
      sql.append(" AND project_id=?");
      args.add(r.queryInt("project_id", 0));
    }
    if (r.query.containsKey("status")) {
      sql.append(" AND status=?");
      args.add(r.query.get("status"));
    }
    sql.append(" ORDER BY id DESC LIMIT ?");
    args.add(r.queryInt("limit", 50));
    return db.query(sql.toString(), args.toArray());
  }

  private Object getRun(Http.Req r) throws Exception {
    Map<String, Object> run = must(db.one("SELECT * FROM runs WHERE id=?", r.id("id")), "run");
    run.put("line_count", db.count("SELECT COUNT(*) FROM run_lines WHERE run_id=?", r.id("id")));
    run.put("steps", db.query("SELECT step_order, label, status, detail, started_at, ended_at FROM run_steps WHERE run_id=? ORDER BY step_order", r.id("id")));
    return run;
  }

  /** Long-poll: ?after=<seq>&wait=<seconds> returns as soon as new lines exist or the run ends. */
  private Object runLog(Http.Req r) throws Exception {
    long id = r.id("id");
    must(db.one("SELECT id FROM runs WHERE id=?", id), "run");
    int after = r.queryInt("after", 0);
    long deadline = System.currentTimeMillis() + Math.min(60, Math.max(0, r.queryInt("wait", 0))) * 1000L;
    while (true) {
      List<Map<String, Object>> lines = db.query("SELECT seq, at, line FROM run_lines WHERE run_id=? AND seq>? ORDER BY seq LIMIT 1000", id, after);
      String status = String.valueOf(db.one("SELECT status FROM runs WHERE id=?", id).get("status"));
      boolean finished = !status.equals("queued") && !status.equals("running") && !status.equals("new");
      if (!lines.isEmpty() || finished || System.currentTimeMillis() >= deadline) {
        long next = lines.isEmpty() ? after : ((Number) lines.get(lines.size() - 1).get("seq")).longValue();
        return Json.map("run_id", id, "status", status, "finished", finished, "next", next, "lines", lines);
      }
      Thread.sleep(250);
    }
  }

  /** Plain text, chunked, until the run ends. Made for curl. */
  private Object runLogStream(Http.Req r) throws Exception {
    long id = r.id("id");
    must(db.one("SELECT id FROM runs WHERE id=?", id), "run");
    HttpExchange ex = r.exchange;
    ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
    ex.getResponseHeaders().set("Cache-Control", "no-cache");
    ex.sendResponseHeaders(200, 0);
    long after = 0;
    try (OutputStream out = ex.getResponseBody()) {
      while (true) {
        List<Map<String, Object>> lines = db.query("SELECT seq, line FROM run_lines WHERE run_id=? AND seq>? ORDER BY seq LIMIT 1000", id, after);
        for (Map<String, Object> l : lines) {
          out.write((l.get("line") + "\n").getBytes(StandardCharsets.UTF_8));
          after = ((Number) l.get("seq")).longValue();
        }
        Map<String, Object> run = db.one("SELECT status, error, duration_ms FROM runs WHERE id=?", id);
        String status = String.valueOf(run.get("status"));
        if (!status.equals("queued") && !status.equals("running") && !status.equals("new")) {
          if (lines.isEmpty() || db.count("SELECT COUNT(*) FROM run_lines WHERE run_id=? AND seq>?", id, after) == 0) {
            out.write(String.format("--- run %d %s%s (%s ms)%n", id, status, run.get("error") == null ? "" : ": " + run.get("error"), run.get("duration_ms")).getBytes(StandardCharsets.UTF_8));
            break;
          }
        }
        out.flush();
        if (lines.isEmpty()) Thread.sleep(300);
      }
    }
    return Http.HANDLED;
  }

  private Object abortRun(Http.Req r) throws Exception {
    long id = r.id("id");
    Map<String, Object> run = must(db.one("SELECT id, status FROM runs WHERE id=?", id), "run");
    String status = String.valueOf(run.get("status"));
    String outcome;
    if (status.equals("queued") || status.equals("new")) {
      db.update("UPDATE runs SET status='aborted', error='aborted before start', ended_at=? WHERE id=?", Db.now(), id);
      outcome = "removed from queue";
    } else if (status.equals("running")) {
      outcome = engine.abort(id) ? "abort requested" : "run just finished";
    } else {
      throw new Http.ApiError(409, "run is already " + status);
    }
    audit(r, "run.abort", "run", id, outcome);
    return Json.map("run_id", id, "outcome", outcome);
  }

  private Object downloadArtifact(Http.Req r) throws Exception {
    Map<String, Object> a = must(db.one("SELECT * FROM artifacts WHERE id=?", r.id("id")), "artifact");
    Path p = Path.of(String.valueOf(a.get("path")));
    if (!Files.exists(p)) throw new Http.ApiError(410, "artifact file is gone: " + a.get("filename"));
    return new Http.Raw(200, String.valueOf(a.get("content_type")), Files.readAllBytes(p));
  }

  // ---------------------------------------------------------------- keys

  private Object createKey(Http.Req r) throws Exception {
    JsonNode b = r.body();
    String name = Json.req(b, "name");
    String scopes = b.hasNonNull("scopes") ? Json.req(b, "scopes") : "read";
    if (!Auth.validScopes(scopes)) throw new Http.ApiError(400, "scopes: comma-separated subset of " + Auth.SCOPES);
    String clear = auth.newClearKey();
    long id;
    try {
      id = db.insert("INSERT INTO api_keys(name, key_hash, scopes, created_at) VALUES (?,?,?,?)", name, Auth.hash(clear), scopes, Db.now());
    } catch (SQLException e) {
      throw conflict(e, "key name already exists");
    }
    audit(r, "key.create", "api_key", id, name + " [" + scopes + "]");
    return Json.map("id", id, "name", name, "scopes", scopes, "key", clear, "note", "shown once, store it now");
  }

  private Object revokeKey(Http.Req r) throws Exception {
    long id = r.id("id");
    must(db.one("SELECT id FROM api_keys WHERE id=?", id), "key");
    if (r.key != null && r.key.id() == id) throw new Http.ApiError(400, "a key cannot revoke itself");
    db.update("UPDATE api_keys SET revoked_at=? WHERE id=? AND revoked_at IS NULL", Db.now(), id);
    audit(r, "key.revoke", "api_key", id, null);
    return Json.map("id", id, "revoked", true);
  }

  // ---------------------------------------------------------------- helpers

  private void audit(Http.Req r, String action, String entity, Long entityId, String detail) throws SQLException {
    db.insert("INSERT INTO audit_log(at, actor, action, entity, entity_id, detail) VALUES (?,?,?,?,?,?)", Db.now(), r.actor(), action, entity, entityId, detail);
  }

  private static Map<String, Object> must(Map<String, Object> row, String what) {
    if (row == null) throw new Http.ApiError(404, what + " not found");
    return row;
  }

  private static Object pick(JsonNode b, String field, Map<String, Object> current) {
    return b != null && b.has(field) ? Json.str(b, field) : current.get(field);
  }

  private static Http.ApiError conflict(SQLException e, String message) {
    if (e.getMessage() != null && e.getMessage().contains("UNIQUE")) return new Http.ApiError(409, message);
    throw new IllegalStateException(e);
  }
}
