package org.skypulse.handlers.services;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.PathTemplateMatch;
import io.undertow.util.StatusCodes;
import org.skypulse.config.database.DatabaseUtil;
import org.skypulse.utils.ResponseUtil;

import java.util.*;

public class GetSingleMonitoredServiceHandler implements HttpHandler {

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        if (exchange.isInIoThread()) {
            exchange.dispatch(this);
            return;
        }

        // Extract UUID from the path
        PathTemplateMatch pathMatch = exchange.getAttachment(PathTemplateMatch.ATTACHMENT_KEY);
        String uuid = pathMatch.getParameters().get("uuid");

        if (uuid == null || uuid.isBlank()) {
            ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "UUID is required");
            return;
        }

        // Query DB
        String sql = "SELECT * FROM monitored_services WHERE uuid = ?";
        List<Object> params = List.of(uuid);

        List<Map<String, Object>> result = DatabaseUtil.query(sql, params);

        if (result.isEmpty()) {
            ResponseUtil.sendError(exchange, StatusCodes.NOT_FOUND, "Service not found");
            return;
        }

        // Return single service
        ResponseUtil.sendSuccess(exchange, "Service fetched successfully", result.get(0));
    }
}
