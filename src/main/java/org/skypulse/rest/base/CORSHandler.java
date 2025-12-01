


package org.skypulse.rest.base;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;

public class CORSHandler implements HttpHandler {
    private final HttpHandler next;
    private final String[] allowedOrigins;

    public CORSHandler(HttpHandler next, String[] allowedOrigins) {
        this.next = next;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        boolean allowed = false;

        if (origin != null) {
            for (String o : allowedOrigins) {
                if (o.equalsIgnoreCase(origin)) {
                    allowed = true;
                    break;
                }
            }
        }

        if (allowed) {
            exchange.getResponseHeaders().put(new HttpString("Access-Control-Allow-Origin"), origin);
            exchange.getResponseHeaders().put(new HttpString("Access-Control-Allow-Credentials"), "true");
            exchange.getResponseHeaders().put(new HttpString("Access-Control-Allow-Methods"), "GET, POST, OPTIONS, PUT, PATCH, DELETE");
            exchange.getResponseHeaders().put(new HttpString("Access-Control-Allow-Headers"), "Authorization, AuthToken, RequestReference, Content-Type");
        }

        if (exchange.getRequestMethod().equalToString("OPTIONS")) {
            exchange.setStatusCode(204);
            exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, "0");
            exchange.endExchange();
            return;
        }

        next.handleRequest(exchange);
    }
}
