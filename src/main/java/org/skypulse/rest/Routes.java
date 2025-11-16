package org.skypulse.rest;

import io.undertow.Handlers;
import io.undertow.server.RoutingHandler;
import io.undertow.server.handlers.BlockingHandler;
import org.skypulse.auth.AuthRegisterHandler;
import org.skypulse.config.base.Dispatcher;
import org.skypulse.config.base.FallBack;

public class Routes {
    public static RoutingHandler auth() {
        return Handlers.routing()
                .post("/register", new Dispatcher(new BlockingHandler(new AuthRegisterHandler())))
                .setInvalidMethodHandler(new Dispatcher(new FallBack()));
    }
}
