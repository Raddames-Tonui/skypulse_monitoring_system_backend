package org.skypulse.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.StatusCodes;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * Parsing JSON, extracting fields
 * */
public class HttpRequestUtil {
    public static Map<String, Object> parseJson(HttpServerExchange exchange)  {
        try (InputStream is = exchange.getInputStream()) {
            // Jackson needs a TypeReference to preserve generics
            return JsonUtil.mapper().readValue(is, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Invalid JSON: " + e.getMessage());
            return null;
        }
    }

    public static String getString(Map<String, Object> body, String field) {
        Object value = body.get(field);
        return value != null ? value.toString() : null;
    }

    public static Integer getInteger(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).intValue();
        return Integer.parseInt(value.toString());
    }

    public static Long getLong(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).longValue();
        return Long.parseLong(value.toString());
    }
}



/**
 *  package org.skypulse.utils;

 package org.skypulse.utils;

 import com.fasterxml.jackson.databind.ObjectMapper;
 import io.undertow.server.HttpServerExchange;
 import io.undertow.util.StatusCodes;

 import java.io.InputStream;
 import java.util.Map;

 public class HttpRequestUtil {

 private static final ObjectMapper mapper = JsonUtil.mapper();

 @SuppressWarnings("unchecked")
 public static Map<String, Object> parseJson(HttpServerExchange exchange) {
 try (InputStream is = exchange.getInputStream()) {
 return mapper.readValue(is, Map.class);
 } catch (Exception e) {
 ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Invalid JSON: " + e.getMessage());
 return null;
 }
 }

 public static String getString(Map<String, Object> body, String field) {
 Object v = body.get(field);
 return v != null ? v.toString() : null;
 }

 public static Integer getInt(Map<String, Object> body, String field) {
 Object v = body.get(field);
 if (v == null) return null;
 if (v instanceof Number) return ((Number) v).intValue();
 return Integer.parseInt(v.toString());
 }

 public static Long getLong(Map<String, Object> body, String field) {
 Object v = body.get(field);
 if (v == null) return null;
 if (v instanceof Number) return ((Number) v).longValue();
 return Long.parseLong(v.toString());
 }
 }


  * */