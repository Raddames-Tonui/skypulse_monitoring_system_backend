package org.skypulse.config.base;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;

public class InvalidMethod implements HttpHandler {
    @Override
    public void handleRequest(HttpServerExchange exchange) {
        exchange.setStatusCode(StatusCodes.OK);

        //TODO: use JSON instead of plain text

        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
        exchange.getResponseSender().send("Method " + exchange.getRequestMethod() + " not allowed");
    }
}
