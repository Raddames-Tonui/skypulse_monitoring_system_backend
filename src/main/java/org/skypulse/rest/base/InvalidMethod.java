package org.skypulse.rest.base;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.StatusCodes;
import org.skypulse.utils.ResponseUtil;

/**
 * Handles unsupported HTTP methods
 * */
public class InvalidMethod implements HttpHandler {

    @Override
    public void handleRequest(HttpServerExchange exchange) {
        String method = exchange.getRequestMethod().toString();
        String message = "Method " + method + " not allowed";

        ResponseUtil.sendError(exchange, StatusCodes.METHOD_NOT_ALLOWED, message);
    }
}
