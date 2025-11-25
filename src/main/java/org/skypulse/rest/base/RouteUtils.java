package org.skypulse.rest.base;

import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.BlockingHandler;
import org.skypulse.rest.auth.AuthMiddleware;

public class RouteUtils {

    public static HttpHandler open(HttpHandler handler) {
        return new Dispatcher(
                new BlockingHandler(handler)
        );
    }

    public static HttpHandler secure(HttpHandler handler, long accessTokenTtl) {
        return new Dispatcher(
                new BlockingHandler(
                        new AuthMiddleware(handler, accessTokenTtl)
                )
        );
    }
}
