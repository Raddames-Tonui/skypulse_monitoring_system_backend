package org.skypulse.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.SerializationFeature;

public class JsonUtil {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}



/**
 * Replace: Map<String, Object> body = JsonUtil.mapper().readValue(is, Map.class);
 * with
 * Map<String, Object> body =
 *         JsonUtil.mapper().readValue(is, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
 * to remove:
 *    -  unchecked assignment warnings
 *     - raw type warnings
 *      - IDE yellow highlights
 * **/