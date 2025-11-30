package org.skypulse.utils;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.StatusCodes;

public final class ValidationUtil {

    private ValidationUtil() {}

    public static boolean requireNonNull(HttpServerExchange exchange, Object value, String fieldName) {
        if (value == null) {
            ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, fieldName + " is required");
            return false;
        }
        return true;
    }

    public static boolean requireNonBlank(HttpServerExchange exchange, String value, String fieldName) {
        if (value == null || value.isBlank()) {
            ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, fieldName + " is required or blank");
            return false;
        }
        return true;
    }

    public static boolean requirePositive(HttpServerExchange exchange, Number value, String fieldName) {
        if (value == null || value.longValue() <= 0) {
            ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, fieldName + " must be positive");
            return false;
        }
        return true;
    }

    public static boolean validEmail(HttpServerExchange exchange, String email) {
        if (email == null || !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Invalid email");
            return false;
        }
        return true;
    }
}
