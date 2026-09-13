package org.oculix.runner;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One SQLite connection, serialized: SQLite has a single writer anyway and the
 * service's queries are short. Rows come back as ordered maps so they can be
 * serialized straight to JSON.
 */
public final class Db implements AutoCloseable {

  private final Connection c;

  public Db(Path file) throws SQLException, IOException {
    c = DriverManager.getConnection("jdbc:sqlite:" + file);
    try (Statement s = c.createStatement()) {
      s.execute("PRAGMA journal_mode=WAL");
      s.execute("PRAGMA foreign_keys=ON");
      s.execute("PRAGMA busy_timeout=5000");
    }
    migrate();
  }

  private void migrate() throws SQLException, IOException {
    String sql;
    try (InputStream in = Db.class.getResourceAsStream("/schema.sql")) {
      if (in == null) throw new IOException("schema.sql missing from the jar");
      sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    synchronized (this) {
      try (Statement s = c.createStatement()) {
        // Comments first, then statements: a comment may well contain a semicolon.
        String noComments = sql.replaceAll("(?m)^\\s*--.*$", "");
        for (String stmt : noComments.split(";")) {
          String t = stmt.trim();
          if (!t.isEmpty()) s.execute(t);
        }
      }
    }
  }

  public static String now() {
    return DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.MILLIS));
  }

  public synchronized long insert(String sql, Object... args) throws SQLException {
    try (PreparedStatement p = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
      bind(p, args);
      p.executeUpdate();
      try (ResultSet k = p.getGeneratedKeys()) {
        return k.next() ? k.getLong(1) : -1;
      }
    }
  }

  public synchronized int update(String sql, Object... args) throws SQLException {
    try (PreparedStatement p = c.prepareStatement(sql)) {
      bind(p, args);
      return p.executeUpdate();
    }
  }

  public synchronized List<Map<String, Object>> query(String sql, Object... args) throws SQLException {
    try (PreparedStatement p = c.prepareStatement(sql)) {
      bind(p, args);
      try (ResultSet r = p.executeQuery()) {
        ResultSetMetaData md = r.getMetaData();
        int n = md.getColumnCount();
        List<Map<String, Object>> rows = new ArrayList<>();
        while (r.next()) {
          Map<String, Object> row = new LinkedHashMap<>();
          for (int i = 1; i <= n; i++) row.put(md.getColumnLabel(i), r.getObject(i));
          rows.add(row);
        }
        return rows;
      }
    }
  }

  public Map<String, Object> one(String sql, Object... args) throws SQLException {
    List<Map<String, Object>> rows = query(sql, args);
    return rows.isEmpty() ? null : rows.get(0);
  }

  public long count(String sql, Object... args) throws SQLException {
    Map<String, Object> row = one(sql, args);
    Object v = row == null ? null : row.values().iterator().next();
    return v instanceof Number ? ((Number) v).longValue() : 0;
  }

  private static void bind(PreparedStatement p, Object[] args) throws SQLException {
    for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]);
  }

  @Override
  public synchronized void close() throws SQLException {
    c.close();
  }
}
