package org.skypulse.services.sse;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.undertow.server.handlers.sse.ServerSentEventConnection;
import io.undertow.server.handlers.sse.ServerSentEventConnectionCallback;
import io.undertow.server.handlers.sse.ServerSentEventHandler;
import org.skypulse.utils.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class SseHandler implements ServerSentEventConnectionCallback {

    protected final Logger logger = LoggerFactory.getLogger(SseHandler.class);
    protected final Set<ServerSentEventConnection> connections = Collections.newSetFromMap(new ConcurrentHashMap<>());
    protected final ServerSentEventHandler handler;
    protected final ScheduledExecutorService scheduler;

    public SseHandler(long initialDelaySec, long intervalSec) {
        this.handler = new ServerSentEventHandler(this);
        this.scheduler = Executors.newSingleThreadScheduledExecutor();

        if (intervalSec <= 0) {
            intervalSec = 30;
        }

        this.scheduler.scheduleAtFixedRate(this::pushToAll, initialDelaySec, intervalSec, TimeUnit.SECONDS);
    }


    public ServerSentEventHandler getHandler(){
        return handler;
    }

    public void shutdown(){
        if (!scheduler.isShutdown()){
            scheduler.shutdown();
            logger.info("{} scheduler shut down.", getClass().getSimpleName());
        }
    }

    @Override
    public void connected(ServerSentEventConnection connection, String lastEventId) {
        connections.add(connection);
        connection.addCloseTask((connections::remove));
        push(connection);
        logger.info("New SSE connection established (Last Event ID: {}). Total connections: {}",
                lastEventId, connections.size());
    }


    private void pushToAll() {
        Map<String, Object> data = generateData();
        String json = serializeToJson(data);
        for (ServerSentEventConnection conn : connections) {
            if (conn.isOpen()) conn.send(json);
        }
    }

    public void push(ServerSentEventConnection connection) {
        Map<String, Object> data = generateData();
        String json = serializeToJson(data);
        if (json != null && connection.isOpen()) {
            connection.send(json);
        }
    }

    private String serializeToJson(Map<String, Object> data) {
        try {
            return JsonUtil.mapper().writeValueAsString(data);
        } catch (JsonProcessingException e) {
            logger.error("Error serializing SSE payload: {}", e.getMessage(), e);
            return null;
        }
    }


    // Subclasses Override to provide data for SSE push
    protected Map<String, Object> generateData() {
        return null;
    }
}
