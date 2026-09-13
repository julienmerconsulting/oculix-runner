package org.oculix.runner;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * API keys with scopes. Scopes are ordered: admin implies run, run implies read.
 * The clear key is shown exactly once, at creation; the base keeps its SHA-256.
 */
public final class Auth {

  public static final List<String> SCOPES = List.of("read", "run", "admin");

  public record Key(long id, String name, Set<String> scopes) { }

  private final Db db;
  private final SecureRandom random = new SecureRandom();

  public Auth(Db db) {
    this.db = db;
  }

  /** Creates the first admin key when the base has none. Returns the clear key, or null. */
  public String bootstrap(String fixedKey) throws SQLException {
    if (db.count("SELECT COUNT(*) FROM api_keys") > 0) return null;
    String clear = fixedKey != null ? fixedKey : newClearKey();
    db.insert("INSERT INTO api_keys(name, key_hash, scopes, created_at) VALUES (?,?,?,?)",
        "bootstrap-admin", hash(clear), "admin", Db.now());
    return clear;
  }

  public String newClearKey() {
    byte[] b = new byte[24];
    random.nextBytes(b);
    return "orx_" + hex(b);
  }

  public Key require(String clearKey, String scope) {
    if (clearKey == null || clearKey.isBlank()) throw new Http.ApiError(401, "X-Api-Key header required");
    Map<String, Object> row;
    try {
      row = db.one("SELECT id, name, scopes FROM api_keys WHERE key_hash = ? AND revoked_at IS NULL", hash(clearKey));
      if (row == null) throw new Http.ApiError(401, "invalid or revoked key");
      db.update("UPDATE api_keys SET last_used_at = ? WHERE id = ?", Db.now(), row.get("id"));
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
    Set<String> scopes = expand(String.valueOf(row.get("scopes")));
    if (!scopes.contains(scope)) throw new Http.ApiError(403, "key '" + row.get("name") + "' lacks scope " + scope);
    return new Key(((Number) row.get("id")).longValue(), String.valueOf(row.get("name")), scopes);
  }

  /** "run" grants read too, "admin" grants everything. */
  public static Set<String> expand(String scopesCsv) {
    Set<String> out = new HashSet<>();
    for (String s : scopesCsv.split(",")) {
      int level = SCOPES.indexOf(s.trim());
      if (level < 0) throw new Http.ApiError(400, "unknown scope: " + s.trim());
      out.addAll(SCOPES.subList(0, level + 1));
    }
    return out;
  }

  public static String hash(String clear) {
    try {
      return hex(MessageDigest.getInstance("SHA-256").digest(clear.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String hex(byte[] b) {
    StringBuilder sb = new StringBuilder(b.length * 2);
    for (byte x : b) sb.append(String.format("%02x", x));
    return sb.toString();
  }

  static boolean validScopes(String csv) {
    return Arrays.stream(csv.split(",")).map(String::trim).allMatch(SCOPES::contains);
  }
}
