package org.oculix.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A router on top of the JDK's HttpServer. Routes are "METHOD /path/{param}".
 * Handlers return a value serialized as JSON, a {@link Raw} body, or
 * {@link #HANDLED} when they wrote the response themselves (streams, files).
 */
public final class Http {

  public static final class ApiError extends RuntimeException {
    public final int status;
    public ApiError(int status, String message) {
      super(message);
      this.status = status;
    }
  }

  /** A non-JSON response body. */
  public record Raw(int status, String contentType, byte[] body) { }

  public static final Object HANDLED = new Object();

  public static final class Req {
    public final HttpExchange exchange;
    public final Map<String, String> params;
    public final Map<String, String> query;
    public Auth.Key key;
    private JsonNode body;

    Req(HttpExchange exchange, Map<String, String> params, Map<String, String> query) {
      this.exchange = exchange;
      this.params = params;
      this.query = query;
    }

    public long id(String name) {
      try {
        return Long.parseLong(params.get(name));
      } catch (NumberFormatException e) {
        throw new ApiError(400, "invalid id: " + params.get(name));
      }
    }

    public JsonNode body() throws IOException {
      if (body == null) body = Json.parse(exchange.getRequestBody());
      return body;
    }

    public int queryInt(String name, int fallback) {
      String v = query.get(name);
      if (v == null || v.isBlank()) return fallback;
      try {
        return Integer.parseInt(v);
      } catch (NumberFormatException e) {
        throw new ApiError(400, "query parameter " + name + " must be a number");
      }
    }

    public String actor() {
      return key == null ? "anonymous" : key.name();
    }
  }

  @FunctionalInterface
  public interface Handler {
    Object handle(Req req) throws Exception;
  }

  private record Route(String method, Pattern pattern, List<String> names, String scope, Handler handler) { }

  private final List<Route> routes = new ArrayList<>();
  private final Auth auth;

  public Http(Auth auth) {
    this.auth = auth;
  }

  /** scope: null = public, otherwise the scope the key must hold. */
  public void route(String method, String path, String scope, Handler handler) {
    List<String> names = new ArrayList<>();
    Matcher m = Pattern.compile("\\{(\\w+)}").matcher(path);
    StringBuilder rx = new StringBuilder("^");
    int last = 0;
    while (m.find()) {
      rx.append(Pattern.quote(path.substring(last, m.start()))).append("([^/]+)");
      names.add(m.group(1));
      last = m.end();
    }
    rx.append(Pattern.quote(path.substring(last))).append("$");
    routes.add(new Route(method, Pattern.compile(rx.toString()), names, scope, handler));
  }

  public HttpServer start(int port) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 64);
    server.setExecutor(Executors.newFixedThreadPool(8));
    server.createContext("/", this::dispatch);
    server.start();
    return server;
  }

  private void dispatch(HttpExchange ex) throws IOException {
    try {
      String method = ex.getRequestMethod();
      String path = ex.getRequestURI().getPath();
      boolean pathSeen = false;
      for (Route r : routes) {
        Matcher m = r.pattern().matcher(path);
        if (!m.matches()) continue;
        pathSeen = true;
        if (!r.method().equals(method)) continue;
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i < r.names().size(); i++) params.put(r.names().get(i), m.group(i + 1));
        Req req = new Req(ex, params, parseQuery(ex.getRequestURI().getRawQuery()));
        if (r.scope() != null) {
          // Header first; ?key= as a fallback for things a browser opens directly (the live MJPEG view).
          String key = ex.getRequestHeaders().getFirst("X-Api-Key");
          if (key == null) key = req.query.get("key");
          req.key = auth.require(key, r.scope());
        }
        Object result = r.handler().handle(req);
        if (result == HANDLED) return;
        if (result instanceof Raw raw) {
          send(ex, raw.status(), raw.contentType(), raw.body());
        } else {
          send(ex, 200, "application/json", Json.bytes(result));
        }
        return;
      }
      throw new ApiError(pathSeen ? 405 : 404, pathSeen ? "method not allowed" : "not found: " + path);
    } catch (ApiError e) {
      send(ex, e.status, "application/json", Json.bytes(Json.map("error", e.getMessage())));
    } catch (Exception e) {
      System.err.println("[runner] " + ex.getRequestMethod() + " " + ex.getRequestURI() + " failed: " + e);
      e.printStackTrace();
      send(ex, 500, "application/json", Json.bytes(Json.map("error", e.toString())));
    } finally {
      ex.close();
    }
  }

  public static void send(HttpExchange ex, int status, String contentType, byte[] body) throws IOException {
    ex.getResponseHeaders().set("Content-Type", contentType + (contentType.startsWith("text/") ? "; charset=utf-8" : ""));
    ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
    if (body.length > 0) {
      try (OutputStream out = ex.getResponseBody()) {
        out.write(body);
      }
    }
  }

  private static Map<String, String> parseQuery(String raw) {
    Map<String, String> q = new LinkedHashMap<>();
    if (raw == null || raw.isEmpty()) return q;
    for (String pair : raw.split("&")) {
      int eq = pair.indexOf('=');
      String k = eq < 0 ? pair : pair.substring(0, eq);
      String v = eq < 0 ? "" : pair.substring(eq + 1);
      q.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
    }
    return q;
  }
}
