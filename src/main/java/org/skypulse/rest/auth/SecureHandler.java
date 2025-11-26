package org.skypulse.rest.auth;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.StatusCodes;
import org.skypulse.config.database.dtos.UserContext;
import org.skypulse.utils.ResponseUtil;

import java.util.HashSet;
import java.util.Set;
/**
 * Enforces Role based access Control
 * Check permissions before next handler
 * */
public class SecureHandler implements HttpHandler {

    private final HttpHandler next;
    private final Set<String> allowedRoles;

    public SecureHandler(HttpHandler next, RequireRoles annotation) {
        this.next = next;
        this.allowedRoles = new HashSet<>();
        for (String role : annotation.value()) {
            allowedRoles.add(role.toUpperCase());
        }
    }
    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        UserContext ctx = exchange.getAttachment(UserContext.ATTACHMENT_KEY);
        if(ctx == null){
            ResponseUtil.sendError(exchange, StatusCodes.UNAUTHORIZED, "User context missing");
            return;
        }

        String  role = ctx.roleName().toUpperCase();

        if (!allowedRoles.contains(role)) {
            ResponseUtil.sendError(exchange, StatusCodes.FORBIDDEN, "User not Authorized");
            return;
        }

        next.handleRequest(exchange);
    }
}
