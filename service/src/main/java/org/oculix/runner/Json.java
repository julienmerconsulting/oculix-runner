package org.oculix.runner;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/** Jackson, kept behind a few static helpers. */
public final class Json {

  public static final ObjectMapper MAPPER = new ObjectMapper();

  private Json() { }

  public static byte[] bytes(Object value) {
    try {
      return MAPPER.writeValueAsBytes(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String string(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  public static JsonNode parse(InputStream in) throws IOException {
    byte[] raw = in.readAllBytes();
    if (raw.length == 0) return NullNode.getInstance();
    return MAPPER.readTree(raw);
  }

  public static JsonNode parse(String text) throws IOException {
    if (text == null || text.isBlank()) return NullNode.getInstance();
    return MAPPER.readTree(text);
  }

  /** Optional string field, null when absent or JSON null. */
  public static String str(JsonNode node, String field) {
    JsonNode v = node == null ? null : node.get(field);
    return (v == null || v.isNull()) ? null : v.asText();
  }

  /** Required string field. */
  public static String req(JsonNode node, String field) {
    String v = str(node, field);
    if (v == null || v.isBlank()) throw new Http.ApiError(400, "missing field: " + field);
    return v.trim();
  }

  public static Long lng(JsonNode node, String field) {
    JsonNode v = node == null ? null : node.get(field);
    if (v == null || v.isNull()) return null;
    if (v.isNumber()) return v.asLong();
    try {
      return Long.parseLong(v.asText().trim());
    } catch (NumberFormatException e) {
      throw new Http.ApiError(400, "field " + field + " must be a number");
    }
  }

  public static boolean bool(JsonNode node, String field, boolean fallback) {
    JsonNode v = node == null ? null : node.get(field);
    return (v == null || v.isNull()) ? fallback : v.asBoolean();
  }

  /** Sub-object serialized back to text, null when absent. */
  public static String objectText(JsonNode node, String field) {
    JsonNode v = node == null ? null : node.get(field);
    if (v == null || v.isNull()) return null;
    if (!v.isObject()) throw new Http.ApiError(400, "field " + field + " must be an object");
    return v.toString();
  }

  public static Map<String, Object> map(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
    return m;
  }
}
