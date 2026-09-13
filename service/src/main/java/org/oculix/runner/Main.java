package org.oculix.runner;

import java.nio.file.Files;

/**
 * oculix-runner-service entry point.
 *
 * <pre>
 *   xvfb-run -a -s "-screen 0 1280x1024x24" \
 *     java -cp oculix-runner-service.jar:/opt/oculix/oculix.jar org.oculix.runner.Main
 * </pre>
 *
 * Environment: RUNNER_HTTP_PORT (8765), RUNNER_DATA_DIR (/workdir),
 * OCULIX_JAR (/opt/oculix/oculix.jar), RUNNER_DEBUG_LEVEL (3),
 * RUNNER_DEFAULT_TIMEOUT_MS (0 = none), RUNNER_BOOTSTRAP_KEY (optional).
 */
public final class Main {

  public static void main(String[] args) throws Exception {
    Config cfg = Config.fromEnv();
    Files.createDirectories(cfg.runsDir());
    Files.createDirectories(cfg.artifactsDir());
    if (!Files.isRegularFile(cfg.oculixJar)) {
      System.err.println("[runner] OculiX jar not found: " + cfg.oculixJar);
      System.exit(2);
    }

    Db db = new Db(cfg.dbFile());
    Auth auth = new Auth(db);
    String bootstrapKey = auth.bootstrap(cfg.bootstrapKey);
    if (bootstrapKey != null) {
      System.out.println("[runner] first start: admin key created (shown once, only its hash is stored):");
      System.out.println("[runner]   X-Api-Key: " + bootstrapKey);
    }

    Engine engine = new Engine(cfg, db);
    Api api = new Api(cfg, db, engine, auth);
    Http http = new Http(auth);
    api.mount(http);
    http.start(cfg.httpPort);
    System.out.println("[runner] oculix-runner-service " + Api.VERSION + " listening on http://0.0.0.0:" + cfg.httpPort
        + " (data: " + cfg.dataDir + ", jar: " + cfg.oculixJar + ")");

    engine.start();
  }
}
