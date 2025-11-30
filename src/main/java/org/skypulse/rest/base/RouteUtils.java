package org.skypulse.rest.base;

import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.BlockingHandler;
import org.skypulse.rest.auth.AuthMiddleware;
import org.skypulse.rest.auth.HandlerFactory;

public class RouteUtils {

    // Open routes, no auth or role checks
    public static HttpHandler open(HttpHandler handler) {
        return new Dispatcher(
                new BlockingHandler(handler)
        );
    }

    // Secured routes with JWT and role checks
    public static HttpHandler secure(HttpHandler handler, long accessTokenTtl) {
        HttpHandler wrappedWithRoles = HandlerFactory.build(handler);

        return new Dispatcher(
                new BlockingHandler(
                        new AuthMiddleware(wrappedWithRoles, accessTokenTtl)
                )
        );
    }
}
