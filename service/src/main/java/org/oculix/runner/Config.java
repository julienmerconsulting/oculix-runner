package org.oculix.runner;

import java.nio.file.Path;
import java.nio.file.Paths;

/** Everything configurable, read once from the environment. */
public final class Config {

  public final int httpPort;
  public final Path dataDir;
  public final Path oculixJar;
  public final int debugLevel;
  public final long defaultTimeoutMs;
  /** Optional: a fixed admin key for lab use, instead of a generated one. */
  public final String bootstrapKey;

  private Config(int httpPort, Path dataDir, Path oculixJar, int debugLevel, long defaultTimeoutMs, String bootstrapKey) {
    this.httpPort = httpPort;
    this.dataDir = dataDir;
    this.oculixJar = oculixJar;
    this.debugLevel = debugLevel;
    this.defaultTimeoutMs = defaultTimeoutMs;
    this.bootstrapKey = bootstrapKey;
  }

  public static Config fromEnv() {
    return new Config(
        Integer.parseInt(env("RUNNER_HTTP_PORT", "8765")),
        Paths.get(env("RUNNER_DATA_DIR", "/workdir")).toAbsolutePath(),
        Paths.get(env("OCULIX_JAR", "/opt/oculix/oculix.jar")).toAbsolutePath(),
        Integer.parseInt(env("RUNNER_DEBUG_LEVEL", "3")),
        Long.parseLong(env("RUNNER_DEFAULT_TIMEOUT_MS", "0")),
        System.getenv("RUNNER_BOOTSTRAP_KEY"));
  }

  private static String env(String name, String fallback) {
    String v = System.getenv(name);
    return (v == null || v.isBlank()) ? fallback : v.trim();
  }

  public Path runsDir() { return dataDir.resolve("runs"); }
  public Path artifactsDir() { return dataDir.resolve("artifacts"); }
  public Path dbFile() { return dataDir.resolve("runner.db"); }
}
