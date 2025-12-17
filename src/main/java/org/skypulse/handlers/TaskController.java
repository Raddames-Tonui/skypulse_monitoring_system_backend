package org.skypulse.handlers;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.StatusCodes;
import org.skypulse.rest.auth.RequireRoles;
import org.skypulse.tasks.TaskScheduler;
import org.skypulse.utils.ResponseUtil;

/**
 * HTTP handler for reloading scheduled tasks via API.
 */
@RequireRoles({"ADMIN", "OPERATOR"})
public class TaskController implements HttpHandler {

    private final TaskScheduler taskScheduler;

    public TaskController(TaskScheduler taskScheduler) {
        this.taskScheduler = taskScheduler;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {

        try {
            taskScheduler.reload();
            ResponseUtil.sendSuccess(exchange, "Tasks reloaded successfully", null);
        } catch (Exception e) {
            ResponseUtil.sendError(exchange, StatusCodes.INTERNAL_SERVER_ERROR, "Tasks reload failed");
        }
    }
}
