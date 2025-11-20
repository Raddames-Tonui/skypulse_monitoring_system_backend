package org.skypulse.rest.base;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.StatusCodes;
import org.skypulse.utils.ResponseUtil;

/***
 * Handles unknown routes
 * */
public class FallBack implements HttpHandler {

    @Override
    public void handleRequest(HttpServerExchange exchange) {
        String uri = exchange.getRequestURI();
        String message = "URI " + uri + " not found on server";

        ResponseUtil.sendError(exchange, StatusCodes.NOT_FOUND, message);
    }
}
