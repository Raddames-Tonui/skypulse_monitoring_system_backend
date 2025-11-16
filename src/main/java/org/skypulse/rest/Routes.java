package org.skypulse.rest;

import io.undertow.Handlers;
import io.undertow.server.RoutingHandler;
import io.undertow.server.handlers.BlockingHandler;
import org.skypulse.auth.AuthRegisterHandler;
import org.skypulse.rest.base.Dispatcher;
import org.skypulse.rest.base.FallBack;
import org.skypulse.rest.base.InvalidMethod;

public class Routes {
    public static RoutingHandler auth() {
        return Handlers.routing()
                .post("/register", new Dispatcher(new BlockingHandler(new AuthRegisterHandler())))
//                .post("/login", new Dispatcher(new BlockingHandler(new LoginUser())))
                .setInvalidMethodHandler(new Dispatcher(new InvalidMethod()))
                .setFallbackHandler(new Dispatcher(new FallBack()));
    }
}
