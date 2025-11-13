package org.skypulse.config.base;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;

public class CORSHander implements HttpHandler {
    private final HttpHandler next;

    public CORSHander(HttpHandler next) {
        this.next = next;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        exchange.getResponseHeaders().put(new HttpString("Access-Control-Allow-Origin"), "*");
        exchange.getResponseHeaders().put(new HttpString("Access-Control-Allow-Methods"), "POST, GET, OPTIONS, PUT, PATCH, DELETE");
        exchange.getResponseHeaders().put(new HttpString("Access-Control-Allow-Headers"), "AuthToken,RequestReference,Content-Type");

        // For OPTIONS, send response directly and stop
        if (exchange.getRequestMethod().equalToString("OPTIONS")) {
            exchange.setStatusCode(204);
            exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, "0");
            exchange.endExchange();
            return;
        }

        next.handleRequest(exchange);
    }
}
