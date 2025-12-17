package org.skypulse.rest.base;

import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.BlockingHandler;
import org.skypulse.rest.auth.AuthMiddleware;
import org.skypulse.rest.auth.HandlerFactory;

public class RouteUtils {

    /**
     * Route that does not require authentication.
     * Anyone can access this route.
     */
    public static HttpHandler publicRoute(HttpHandler handler) {
        return new Dispatcher(
                new BlockingHandler(handler)
        );
    }

    /**
     * Route that requires the user to be authenticated (JWT validated)
     * and optionally role checks applied.
     */
    public static HttpHandler userSessionRequired(HttpHandler handler, long accessTokenTtl) {
        HttpHandler wrappedWithRoles = HandlerFactory.build(handler);

        return new Dispatcher(
                new BlockingHandler(
                        new AuthMiddleware(wrappedWithRoles, accessTokenTtl)
                )
        );
    }

    public static HttpHandler secureSSERoute(HttpHandler handler, long accessTokenTtl) {
        HttpHandler wrappedWithRoles = HandlerFactory.build(handler);

        return new AuthMiddleware(wrappedWithRoles, accessTokenTtl);
    }

    /**
     * Route that requires the user to be authenticated and
     * must have specific roles.
     */
    public static HttpHandler roleProtectedRoute(HttpHandler handler, long accessTokenTtl) {
        HttpHandler wrappedWithRoles = HandlerFactory.build(handler); // applies role checks

        return new Dispatcher(
                new BlockingHandler(
                        new AuthMiddleware(wrappedWithRoles, accessTokenTtl)
                )
        );
    }
}
