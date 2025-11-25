package org.skypulse.rest;

import io.undertow.Handlers;
import io.undertow.server.RoutingHandler;
import io.undertow.server.handlers.BlockingHandler;
import org.skypulse.config.utils.XmlConfiguration;
import org.skypulse.handlers.HealthCheckHandler;
import org.skypulse.handlers.SseHealthCheckHandler;
import org.skypulse.handlers.TaskController;
import org.skypulse.handlers.auth.GetUserProfileHandler;
import org.skypulse.handlers.auth.UserLoginHandler;
import org.skypulse.handlers.auth.UserSignupHandler;
import org.skypulse.handlers.auth.LogoutHandler;
import org.skypulse.handlers.contacts.AddMembersToGroupHandler;
import org.skypulse.handlers.contacts.CreateContactGroupHandler;
import org.skypulse.handlers.services.GetMonitoredServiceHandler;
import org.skypulse.handlers.services.GetSingleMonitoredServiceHandler;
import org.skypulse.handlers.services.MonitoredServiceHandler;
import org.skypulse.handlers.settings.SystemSettingsHandlers;
import org.skypulse.rest.base.AuthMiddleware;
import org.skypulse.rest.base.Dispatcher;
import org.skypulse.rest.base.FallBack;
import org.skypulse.rest.base.InvalidMethod;

import static org.skypulse.Main.appScheduler;
import static org.skypulse.rest.auth.HandlerFactory.build;
import static org.skypulse.rest.base.RouteUtils.open;
import static org.skypulse.rest.base.RouteUtils.secure;

public class Routes {

    public static RoutingHandler auth(XmlConfiguration cfg) {
        long accessTokenTtl = Long.parseLong(cfg.jwtConfig.accessToken) * 60;

        return Handlers.routing()
                .post("/login", open(new UserLoginHandler(cfg)))
                .post("/register", open(new UserSignupHandler()))
                .get("/profile", secure(new GetUserProfileHandler(), accessTokenTtl))
                .post("/logout", secure(new LogoutHandler(), accessTokenTtl))
                .setInvalidMethodHandler(new Dispatcher(new InvalidMethod()))
                .setFallbackHandler(new Dispatcher(new FallBack()));
    }


    public static RoutingHandler health(XmlConfiguration cfg) {
        long accessTokenTtl = Long.parseLong(cfg.jwtConfig.accessToken) * 60;

        return Handlers.routing()
                .get("/health", secure(new HealthCheckHandler(), accessTokenTtl))
                .get("/tasks/reload", secure(build(new TaskController(appScheduler)), accessTokenTtl))
                .setInvalidMethodHandler(new Dispatcher(new InvalidMethod()))
                .setFallbackHandler(new Dispatcher(new FallBack()));
    }


    public static RoutingHandler contactGroups(XmlConfiguration cfg) {
        long accessTokenTtl = Long.parseLong(cfg.jwtConfig.accessToken) * 60;

        return Handlers.routing()

                .post("/groups", secure(new CreateContactGroupHandler(), accessTokenTtl))
                .post("/groups/{id}/members",secure(new AddMembersToGroupHandler(), accessTokenTtl))
                .post("/groups/members/{uuid}", secure(new GetSingleMonitoredServiceHandler(), accessTokenTtl))
                .setInvalidMethodHandler(new Dispatcher(new InvalidMethod()))
                .setFallbackHandler(new Dispatcher(new FallBack()));
    }


    public static RoutingHandler monitoredServices(XmlConfiguration cfg) {
        long accessTokenTtl = Long.parseLong(cfg.jwtConfig.accessToken) * 60;

        return Handlers.routing()
                .get("/service", secure(new GetSingleMonitoredServiceHandler(), accessTokenTtl))
                .get("/", secure(new GetMonitoredServiceHandler(), accessTokenTtl))
                .post("/", secure(new MonitoredServiceHandler(), accessTokenTtl))
                .put("/", secure(new MonitoredServiceHandler(), accessTokenTtl))
                .setInvalidMethodHandler(new Dispatcher(new InvalidMethod()))
                .setFallbackHandler(new Dispatcher(new FallBack()));
    }


    public static RoutingHandler systemSettings(XmlConfiguration cfg) {
        long accessTokenTtl = Long.parseLong(cfg.jwtConfig.accessToken) * 60;

        return Handlers.routing()
                .post("/", secure(new SystemSettingsHandlers(), accessTokenTtl))
                .setInvalidMethodHandler(new Dispatcher(new InvalidMethod()))
                .setFallbackHandler(new Dispatcher(new FallBack()));
    }
}
