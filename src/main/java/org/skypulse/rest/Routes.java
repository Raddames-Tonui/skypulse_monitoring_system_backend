package org.skypulse.rest;

import io.undertow.Handlers;
import io.undertow.server.RoutingHandler;
import io.undertow.server.handlers.BlockingHandler;
import org.skypulse.handlers.auth.*;
import org.skypulse.handlers.contacts.AddMembersToGroupHandler;
import org.skypulse.handlers.contacts.CreateContactGroupHandler;
import org.skypulse.handlers.services.GetMonitoredServiceHandler;
import org.skypulse.handlers.services.MonitoredServiceHandler;
import org.skypulse.handlers.settings.SystemSettingsHandlers;
import org.skypulse.rest.base.AuthMiddleware;
import org.skypulse.rest.base.Dispatcher;
import org.skypulse.rest.base.FallBack;
import org.skypulse.rest.base.InvalidMethod;

public class Routes {

    public static RoutingHandler auth() {
        return Handlers.routing()
                .post("/register", new Dispatcher(new BlockingHandler(new UserSignupHandler())))
                .post("/login", new Dispatcher(new BlockingHandler(new UserLoginHandler())))
                .post("/refresh", new Dispatcher(new BlockingHandler(new RefreshTokenHandler())))
                .get("/profile", new Dispatcher( new BlockingHandler( new AuthMiddleware(new GetUserProfileHandler()))))
                .post("/logout", new Dispatcher( new BlockingHandler( new AuthMiddleware(new LogoutHandler()))))
                .setInvalidMethodHandler(new Dispatcher(new InvalidMethod()))
                .setFallbackHandler(new Dispatcher(new FallBack()));
    }

    public static RoutingHandler contactGroups() {
        return Handlers.routing()
                .post("/groups", new Dispatcher(new BlockingHandler(new AuthMiddleware(new CreateContactGroupHandler()))))
                .post("/groups/{id}/members", new Dispatcher(new BlockingHandler(new AuthMiddleware(new AddMembersToGroupHandler()))))
                .setInvalidMethodHandler(new Dispatcher(new InvalidMethod()))
                .setFallbackHandler(new Dispatcher(new FallBack()));
    }

    public static RoutingHandler monitoredServices() {
        return Handlers.routing()
                .get("/", new Dispatcher(new BlockingHandler(new GetMonitoredServiceHandler())))
                .post("/", new Dispatcher(new BlockingHandler(new AuthMiddleware(new MonitoredServiceHandler()))))
                .put("/", new Dispatcher(new BlockingHandler(new AuthMiddleware(new MonitoredServiceHandler()))))
                .setInvalidMethodHandler(new Dispatcher(new InvalidMethod()))
                .setFallbackHandler(new Dispatcher(new FallBack()));
    }public static RoutingHandler systemSettings() {
        return Handlers.routing()
                .post("/", new Dispatcher(new BlockingHandler(new AuthMiddleware(new SystemSettingsHandlers()))))
                .setInvalidMethodHandler(new Dispatcher(new InvalidMethod()))
                .setFallbackHandler(new Dispatcher(new FallBack()));
    }
}
