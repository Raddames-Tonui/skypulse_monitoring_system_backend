package org.skypulse.handlers;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import org.skypulse.rest.auth.RequireRoles;
import org.skypulse.rest.auth.SecureHandler;
import org.skypulse.services.TaskScheduler;
import org.skypulse.utils.JsonUtil;

import java.util.HashMap;
import java.util.Map;

/**
 * HTTP handler for reloading scheduled tasks via API.
 * Only accessible by authorized users with ADMIN or OPERATOR roles.
 */
@RequireRoles({"ADMIN", "OPERATOR"})
public class TaskController implements HttpHandler {

    private final TaskScheduler taskScheduler;

    public TaskController(TaskScheduler taskScheduler) {
        this.taskScheduler = taskScheduler;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        if (exchange.isInIoThread()) {
            exchange.dispatch(this);
            return;
        }

        Map<String, Object> response = new HashMap<>();

        try {
            taskScheduler.reload();
            response.put("status", "success");
            response.put("message", "Tasks reloaded successfully");
        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", "Failed to reload tasks: " + e.getMessage());
        }

        String json = JsonUtil.mapper().writeValueAsString(response);
        exchange.getResponseSender().send(json);
    }
}
