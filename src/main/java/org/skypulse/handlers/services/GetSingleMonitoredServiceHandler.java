package org.skypulse.handlers.services;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.StatusCodes;
import org.skypulse.config.database.DatabaseUtil;
import org.skypulse.utils.ResponseUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class GetSingleMonitoredServiceHandler implements HttpHandler {
    private static final Logger logger = LoggerFactory.getLogger(GetSingleMonitoredServiceHandler.class);

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        if (exchange.isInIoThread()) {
            exchange.dispatch(this);
            return;
        }

        Deque<String> uuidParam = exchange.getQueryParameters().get("uuid");
        if (uuidParam == null || uuidParam.isEmpty()) {
            ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Missing uuid parameter");
            return;
        }

        String uuid = uuidParam.getFirst();
        logger.debug("GetSingleMonitoredServiceHandler uuid={}", uuid);

        UUID uuidValue;
        try {
            uuidValue = UUID.fromString(uuid);
        } catch (IllegalArgumentException e) {
            ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Invalid UUID format");
            return;
        }

        String sql = "SELECT * FROM monitored_services WHERE uuid = ?";
        List<Map<String, Object>> result = DatabaseUtil.query(sql, List.of(uuidValue));

        if (result.isEmpty()) {
            ResponseUtil.sendError(exchange, StatusCodes.NOT_FOUND, "Service not found");
            return;
        }

        Map<String, Object> service = new HashMap<>(result.getFirst());
        service.remove("monitored_service_id");

        ResponseUtil.sendJson(exchange, StatusCodes.OK, service);
    }


}
