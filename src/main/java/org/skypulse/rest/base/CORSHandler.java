


package org.skypulse.rest.base;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;

public class CORSHandler implements HttpHandler {
    private final HttpHandler next;
    private final String allowedOrigin;

    public CORSHandler(HttpHandler next, String allowedOrigin) {
        this.next = next;
        this.allowedOrigin = allowedOrigin;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {

        exchange.getResponseHeaders().put(new HttpString("Access-Control-Allow-Origin"), allowedOrigin);
        exchange.getResponseHeaders().put(new HttpString("Access-Control-Allow-Origin"), "*");
        exchange.getResponseHeaders().put(new HttpString("Access-Control-Allow-Credentials"), "true");
        exchange.getResponseHeaders().put(new HttpString("Access-Control-Allow-Methods"), "GET, POST, OPTIONS, PUT, PATCH, DELETE");
        exchange.getResponseHeaders().put(new HttpString("Access-Control-Allow-Headers"), "Authorization, AuthToken, RequestReference, Content-Type");

        if (exchange.getRequestMethod().equalToString("OPTIONS")) {
            exchange.setStatusCode(204);
            exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, "0");
            exchange.endExchange();
            return;
        }

        next.handleRequest(exchange);
    }
}

