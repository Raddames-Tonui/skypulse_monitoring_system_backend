package org.skypulse.rest.base;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;

/**
 *  HTTP request is handled on a thread. There are two main kinds of threads:
 *      - IO threads – handle reading the incoming request and writing the response.
 *      - Worker threads – handle the actual heavy work (like querying a database).
 * Dispatches to worker threads for async execution
 * This tells Undertow:
 * “Stop processing on the I/O thread. Resume handling this request on a worker thread using the provided handler.”
 * Why this matters:
 *      Database calls, File I/O, Network calls, Blocking libraries, CPU-heavy logic
 * If you don’t, you risk:
 *      Thread starvation, Latency spike, Total server stall under load
 *
*/


public class Dispatcher implements HttpHandler {
    private final HttpHandler handler;

    public Dispatcher(HttpHandler handler) {
        this.handler = handler;
    }

    public void handleRequest(HttpServerExchange exchange) throws Exception {
        exchange.dispatch(this.handler);
    }
}
