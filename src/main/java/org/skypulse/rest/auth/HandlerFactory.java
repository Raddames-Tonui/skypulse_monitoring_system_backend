package org.skypulse.rest.auth;

import io.undertow.server.HttpHandler;

/**
 * Wraps handler with SecureHandler if @RequireRoles is found.
 */
public class HandlerFactory {
    public static HttpHandler build(HttpHandler handler) {
        RequireRoles annotation = handler.getClass().getAnnotation(RequireRoles.class);

        if (annotation != null){
            return new SecureHandler(handler, annotation);
        }

        return handler;
    }
}
