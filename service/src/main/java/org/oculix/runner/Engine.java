package org.oculix.runner;

import org.sikuli.basics.Debug;
import org.sikuli.ide.Sikulix;
import org.sikuli.support.Commons;
import org.sikuli.support.runner.IRunner;
import org.sikuli.support.runner.Runner;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * The warm JVM. Loads OculiX once, then executes queued runs one after the
 * other on a single worker thread: the Jython interpreter behind
 * {@code Runner} is a process-wide singleton, so there is exactly one script
 * running at any time. The queue is the {@code runs} table itself, ordered by
 * id, which makes it survive a restart.
 */
public final class Engine {

  public enum State { STARTING, READY, FAILED }

  private final Config cfg;
  private final Db db;
  private final String header;

  private volatile State state = State.STARTING;
  private volatile String stateDetail = "";
  private volatile String oculixVersion = "unknown";
  private volatile String jarSha256 = "unknown";

  private final Object signal = new Object();
  private volatile Long currentRunId = null;
  private volatile boolean abortRequested = false;
  private IRunner jython;
  private final PrintStream realOut = System.out;
  private final PrintStream realErr = System.err;

  public Engine(Config cfg, Db db) throws IOException {
    this.cfg = cfg;
    this.db = db;
    try (InputStream in = Engine.class.getResourceAsStream("/header.py")) {
      if (in == null) throw new IOException("header.py missing from the jar");
      header = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    headerLines = (int) header.chars().filter(ch -> ch == '\n').count();
  }

  private final int headerLines;

  public State state() { return state; }
  public String stateDetail() { return stateDetail; }
  public String oculixVersion() { return oculixVersion; }
  public String jarSha256() { return jarSha256; }
  public Long currentRunId() { return currentRunId; }

  public void start() {
    Thread t = new Thread(this::warmUpThenWork, "oculix-engine");
    t.setDaemon(false);
    t.start();
  }

  /** Wakes the worker: a run was queued. */
  public void poke() {
    synchronized (signal) {
      signal.notifyAll();
    }
  }

  // ---------------------------------------------------------------- warm-up

  private void warmUpThenWork() {
    long t0 = System.nanoTime();
    try {
      event("engine.starting", "jar=" + cfg.oculixJar);
      jarSha256 = sha256(cfg.oculixJar);

      // Commons.setStartClass() only accepts callers named org.sikuli.ide.Sikulix*.
      // This service is a second, legitimate entry point into the same jar, so
      // the field is set directly. If it ever gets renamed, this fails loudly.
      Field f = Commons.class.getDeclaredField("startClass");
      f.setAccessible(true);
      f.set(null, Engine.class);

      // JythonRunner.doInit() opens the IDE window unless it sees a -r argument.
      Sikulix.setStartArgs(new String[]{"-r", cfg.runsDir().toString()});
      Commons.setTempFolder();
      Debug.setDebugLevel(cfg.debugLevel);
      Commons.loadOpenCV();
      event("engine.opencv", String.format("%.1fs", secs(t0)));

      Runner.initRunners();
      jython = Runner.getRunner("jython");
      if (jython == null || !jython.isSupported()) throw new IllegalStateException("Jython runner not available in " + cfg.oculixJar);
      oculixVersion = Commons.getSXVersion();
      event("engine.runners", String.format("%.1fs", secs(t0)));

      // Prime the interpreter so the first user run is already warm.
      Path warm = cfg.runsDir().resolve("_warmup.sikuli");
      Files.createDirectories(warm);
      Files.writeString(warm.resolve("_warmup.py"), "print(\"engine warm\")\n");
      PrintStream sink = new PrintStream(OutputStream.nullOutputStream());
      System.setOut(sink);
      System.setErr(sink);
      int rc;
      try {
        rc = Runner.runScripts(Runner.resolveRelativeFiles(new String[]{warm.toString()}), new String[0], new IRunner.Options());
      } finally {
        System.setOut(realOut);
        System.setErr(realErr);
      }
      if (rc != 0) throw new IllegalStateException("warm-up script returned " + rc);

      // Runs left 'running' by a previous life of the service cannot be resumed.
      db.update("UPDATE runs SET status='error', error='service restarted while running', ended_at=? WHERE status='running'", Db.now());
      db.update("UPDATE runs SET status='error', error='service restarted during creation', ended_at=? WHERE status='new'", Db.now());
      db.update("UPDATE suite_runs SET status='failed', ended_at=? WHERE status='running' AND NOT EXISTS (SELECT 1 FROM runs WHERE runs.suite_run_id = suite_runs.id AND runs.status IN ('queued','running'))", Db.now());

      state = State.READY;
      stateDetail = String.format("OculiX %s ready in %.1fs", oculixVersion, secs(t0));
      event("engine.ready", stateDetail);
      realOut.println("[runner] " + stateDetail);
    } catch (Throwable e) {
      state = State.FAILED;
      stateDetail = e.toString();
      event("engine.failed", e.toString());
      realErr.println("[runner] engine failed: " + e);
      e.printStackTrace(realErr);
      return;
    }
    workLoop();
  }

  // ---------------------------------------------------------------- worker

  private void workLoop() {
    while (true) {
      Map<String, Object> run = null;
      try {
        run = db.one("SELECT * FROM runs WHERE status='queued' ORDER BY id LIMIT 1");
      } catch (SQLException e) {
        realErr.println("[runner] queue query failed: " + e);
      }
      if (run == null) {
        synchronized (signal) {
          try {
            signal.wait(1000);
          } catch (InterruptedException ignored) {
            return;
          }
        }
        continue;
      }
      try {
        execute(run);
      } catch (Throwable e) {
        realErr.println("[runner] run " + run.get("id") + " crashed the worker: " + e);
        e.printStackTrace(realErr);
      }
    }
  }

  private void execute(Map<String, Object> run) throws Exception {
    long runId = ((Number) run.get("id")).longValue();
    long t0 = System.nanoTime();
    String startedAt = Db.now();
    db.update("UPDATE runs SET status='running', started_at=?, oculix_version=?, jar_sha256=? WHERE id=?",
        startedAt, oculixVersion, jarSha256, runId);
    Long suiteRunId = run.get("suite_run_id") == null ? null : ((Number) run.get("suite_run_id")).longValue();
    if (suiteRunId != null) {
      db.update("UPDATE suite_runs SET status='running', started_at=COALESCE(started_at, ?) WHERE id=?", startedAt, suiteRunId);
    }
    currentRunId = runId;
    abortRequested = false;
    realOut.printf("[runner] run %d starting%n", runId);

    // Script folder: <runs>/run_<id>.sikuli/run_<id>.py = header + script, plus run.json.
    Path dir = cfg.runsDir().resolve("run_" + runId + ".sikuli");
    Files.createDirectories(dir);
    Path runJson = dir.resolve("run.json");
    Files.writeString(runJson, buildRunJson(run));
    String script = Files.readString(artifactPath(runId, "script.py"));
    Files.writeString(dir.resolve("run_" + runId + ".py"),
        header.replace("__RUN_JSON__", runJson.toString()) + script);

    Capture capture = new Capture(runId);
    PrintStream cap = new PrintStream(capture, true, StandardCharsets.UTF_8);
    IRunner.Options options = new IRunner.Options();
    long timeout = ((Number) run.get("timeout_ms")).longValue();
    if (timeout > 0) options.setTimeout(timeout);

    int rc;
    String status;
    String error = null;
    System.setOut(cap);
    System.setErr(cap);
    try {
      rc = Runner.runScripts(Runner.resolveRelativeFiles(new String[]{dir.toString()}), new String[0], options);
      if (abortRequested) {
        status = "aborted";
      } else if (jython.isAborted()) {
        status = "timeout";
        error = "timed out after " + timeout + " ms";
      } else if (rc == 0) {
        status = "passed";
      } else {
        status = "failed";
        error = errorLine(options) > 0 ? "script error at line " + errorLine(options) : "exit code " + rc;
      }
    } catch (Throwable e) {
      rc = -1;
      status = "error";
      error = e.toString();
    } finally {
      cap.flush();
      capture.finish();
      System.setOut(realOut);
      System.setErr(realErr);
      currentRunId = null;
    }

    long durationMs = (System.nanoTime() - t0) / 1_000_000;
    db.update("UPDATE runs SET status=?, exit_code=?, error=?, error_line=?, ended_at=?, duration_ms=? WHERE id=?",
        status, rc, error, errorLine(options) > 0 ? errorLine(options) : null, Db.now(), durationMs, runId);
    realOut.printf("[runner] run %d %s in %d ms (%d lines, %d steps)%n", runId, status, durationMs, capture.seq, capture.stepOrder);

    if (suiteRunId != null) settleSuite(suiteRunId, runId, status, ((Number) run.get("continue_on_failure")).intValue() != 0);
  }

  private void settleSuite(long suiteRunId, long runId, String status, boolean continueOnFailure) throws SQLException {
    if (!status.equals("passed") && !continueOnFailure) {
      int skipped = db.update("UPDATE runs SET status='skipped', error=?, ended_at=? WHERE suite_run_id=? AND status='queued'",
          "skipped: run " + runId + " " + status, Db.now(), suiteRunId);
      if (skipped > 0) event("suite.skip", "suite_run " + suiteRunId + ": " + skipped + " run(s) skipped after run " + runId + " " + status);
    }
    if (db.count("SELECT COUNT(*) FROM runs WHERE suite_run_id=? AND status IN ('queued','running')", suiteRunId) > 0) return;
    long aborted = db.count("SELECT COUNT(*) FROM runs WHERE suite_run_id=? AND status='aborted'", suiteRunId);
    long bad = db.count("SELECT COUNT(*) FROM runs WHERE suite_run_id=? AND status IN ('failed','timeout','error','skipped')", suiteRunId);
    String suiteStatus = aborted > 0 ? "aborted" : bad > 0 ? "failed" : "passed";
    db.update("UPDATE suite_runs SET status=?, ended_at=? WHERE id=?", suiteStatus, Db.now(), suiteRunId);
  }

  /** True when something was aborted; false when the run was not running. */
  public boolean abort(long runId) {
    Long current = currentRunId;
    if (current != null && current == runId) {
      abortRequested = true;
      Runner.abortAll();
      return true;
    }
    return false;
  }

  // ---------------------------------------------------------------- capture

  /** Line-buffered stdout/stderr of the running script, straight into the base. */
  private final class Capture extends OutputStream {
    final long runId;
    final ByteArrayOutputStream buf = new ByteArrayOutputStream();
    int seq = 0;
    int stepOrder = 0;
    final Map<String, Long> openSteps = new HashMap<>();

    Capture(long runId) { this.runId = runId; }

    @Override
    public void write(int b) {
      if (b == '\n') emit();
      else if (b != '\r') buf.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) {
      for (int i = off; i < off + len; i++) write(b[i]);
    }

    private void emit() {
      String line = buf.toString(StandardCharsets.UTF_8);
      buf.reset();
      realOut.println("[run " + runId + "] " + line);
      try {
        db.insert("INSERT INTO run_lines(run_id, seq, at, line) VALUES (?,?,?,?)", runId, ++seq, Db.now(), line);
        if (line.startsWith("@@STEP|")) step(line);
      } catch (SQLException e) {
        realErr.println("[runner] run " + runId + " could not store a line: " + e);
      }
    }

    private void step(String line) throws SQLException {
      String[] p = line.split("\\|", 4);
      String label = p.length > 1 ? p[1] : "";
      String status = (p.length > 2 ? p[2] : "INFO").toUpperCase();
      String detail = p.length > 3 ? p[3] : null;
      Long open = openSteps.remove(label);
      if (open != null && !status.equals("START")) {
        db.update("UPDATE run_steps SET status=?, detail=COALESCE(?, detail), ended_at=? WHERE id=?", status, detail, Db.now(), open);
        return;
      }
      long id = db.insert("INSERT INTO run_steps(run_id, step_order, label, status, detail, started_at, ended_at) VALUES (?,?,?,?,?,?,?)",
          runId, ++stepOrder, label, status, detail, Db.now(), status.equals("START") ? null : Db.now());
      if (status.equals("START")) openSteps.put(label, id);
    }

    void finish() {
      if (buf.size() > 0) emit();
      for (Long id : openSteps.values()) {
        try {
          db.update("UPDATE run_steps SET status='INCOMPLETE', ended_at=? WHERE id=?", Db.now(), id);
        } catch (SQLException ignored) { }
      }
      openSteps.clear();
    }
  }

  // ---------------------------------------------------------------- helpers

  /** The runner reports the line in the composed file; the user wants the line in their script. */
  private int errorLine(IRunner.Options options) {
    int composed = options.getErrorLine();
    return composed > headerLines ? composed - headerLines : composed;
  }

  private String buildRunJson(Map<String, Object> run) throws SQLException, IOException {
    Map<String, Object> json = Json.map("id", run.get("id"), "name", run.get("name"));
    json.put("params", run.get("params_json") == null ? Json.map() : Json.parse(String.valueOf(run.get("params_json"))));
    Map<String, Object> target = Json.map();
    if (run.get("target_id") != null) {
      Map<String, Object> t = db.one("SELECT name, kind, host, port, display, secret_ref FROM targets WHERE id=?", run.get("target_id"));
      if (t != null) target = t;
    }
    json.put("target", target);
    return Json.string(json);
  }

  public Path artifactPath(long runId, String filename) throws SQLException {
    Map<String, Object> a = db.one("SELECT path FROM artifacts WHERE run_id=? AND filename=?", runId, filename);
    if (a == null) throw new IllegalStateException("run " + runId + " has no artifact " + filename);
    return Path.of(String.valueOf(a.get("path")));
  }

  public void event(String kind, String detail) {
    try {
      db.insert("INSERT INTO engine_events(at, kind, detail) VALUES (?,?,?)", Db.now(), kind, detail);
    } catch (SQLException e) {
      realErr.println("[runner] could not store event " + kind + ": " + e);
    }
  }

  private static double secs(long t0) {
    return (System.nanoTime() - t0) / 1e9;
  }

  public static String sha256(Path file) throws IOException {
    try (InputStream in = Files.newInputStream(file)) {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] b = new byte[1 << 16];
      int n;
      while ((n = in.read(b)) > 0) md.update(b, 0, n);
      return Auth.hex(md.digest());
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
