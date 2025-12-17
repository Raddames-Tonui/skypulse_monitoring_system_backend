package org.skypulse.rest;

import io.undertow.Handlers;
import io.undertow.server.RoutingHandler;
import org.skypulse.config.utils.XmlConfiguration;
import org.skypulse.handlers.HealthCheckHandler;
import org.skypulse.handlers.TaskController;
import org.skypulse.handlers.auth.*;
import org.skypulse.handlers.contacts.*;
import org.skypulse.handlers.logs.GetAuditLogsHandler;
import org.skypulse.handlers.logs.GetSSLLogsHandler;
import org.skypulse.handlers.logs.GetUptimeLogsHandler;
import org.skypulse.handlers.reports.GenerateSslPdfReports;
import org.skypulse.handlers.reports.GenerateUptimePdfReports;
import org.skypulse.handlers.services.GetMonitoredServices;
import org.skypulse.handlers.services.GetSingleMonitoredServiceHandler;
import org.skypulse.handlers.services.MonitoredServiceHandler;
import org.skypulse.handlers.services.UpdateMonitoredServiceHandler;
import org.skypulse.handlers.settings.GetActiveSystemSettingsHandler;
import org.skypulse.handlers.settings.ListCompanies;
import org.skypulse.handlers.settings.SystemSettingsHandlers;
import org.skypulse.handlers.users.CreateNewUser;
import org.skypulse.handlers.users.GetUserDetailHandler;
import org.skypulse.handlers.users.GetUsersHandler;
import org.skypulse.rest.base.Dispatcher;
import org.skypulse.rest.base.FallBack;
import org.skypulse.rest.base.InvalidMethod;
import org.skypulse.tasks.sse.SseHealthCheckHandler;
import org.skypulse.tasks.sse.SseServiceStatusHandler;

import static org.skypulse.Main.appScheduler;
import static org.skypulse.rest.auth.HandlerFactory.build;
import static org.skypulse.rest.base.RouteUtils.*;

public class Routes {

    public static RoutingHandler auth(XmlConfiguration cfg) {
        long accessToken = Long.parseLong(cfg.jwtConfig.accessToken) * 60;

        return Handlers.routing()
                .post("/login", publicRoute(new UserLoginHandler(cfg)))
                .post("/register", publicRoute(new UserSignupHandler()))
                .get("/profile", userSessionRequired(new GetUserProfileHandler(), accessToken))
                .put("/profile", userSessionRequired(new UpdateUserProfile(), accessToken))
                .post("/logout", userSessionRequired(new LogoutHandler(), accessToken))
                .post("/activate", publicRoute(new ActivateUserHandler()))
                .post("/request/reset-password", publicRoute(new RequestResetPasswordHandler()))
                .post("/reset-password", publicRoute(new ResetPasswordHandler()))
                .setInvalidMethodHandler(new Dispatcher(new InvalidMethod()))
                .setFallbackHandler(new Dispatcher(new FallBack()));
    }

    public static RoutingHandler health(XmlConfiguration cfg) {
        long accessToken = Long.parseLong(cfg.jwtConfig.accessToken) * 60;

        return Handlers.routing()
                .get("/health", publicRoute(new HealthCheckHandler()))
                .get("/tasks/reload", userSessionRequired(build(new TaskController(appScheduler)), accessToken))
                .setInvalidMethodHandler(new Dispatcher(new InvalidMethod()))
                .setFallbackHandler(new Dispatcher(new FallBack()));
    }

    public static RoutingHandler users(XmlConfiguration cfg) {
        long accessToken = Long.parseLong(cfg.jwtConfig.accessToken) * 60;

        return Handlers.routing()
                .get("", userSessionRequired(new GetUsersHandler(), accessToken))
                .get("/user", userSessionRequired(new GetUserDetailHandler(), accessToken))
                .post("/user/create", userSessionRequired(new CreateNewUser(), accessToken))
                .setInvalidMethodHandler(new Dispatcher(new InvalidMethod()))
                .setFallbackHandler(new Dispatcher(new FallBack()));
    }

    public static RoutingHandler contactGroups(XmlConfiguration cfg) {
        long accessToken = Long.parseLong(cfg.jwtConfig.accessToken) * 60;

        return Handlers.routing()
                .post("/groups", userSessionRequired(new CreateContactGroupHandler(), accessToken))
                .post("/groups/{uuid}/members", userSessionRequired(new AddMembersToGroupHandler(), accessToken))
                .post("/groups/{uuid}/services", userSessionRequired(new AddServicesToGroupHandler(), accessToken))
                .post("/groups/members/{uuid}", userSessionRequired(new GetSingleMonitoredServiceHandler(), accessToken))
                .get("/groups", userSessionRequired(new GetContactGroupsHandler(), accessToken))
                .get("/group", userSessionRequired(new GetSingleContactGroupHandler(), accessToken))
                .setInvalidMethodHandler(new Dispatcher(new InvalidMethod()))
                .setFallbackHandler(new Dispatcher(new FallBack()));
    }

    public static RoutingHandler monitoredServices(XmlConfiguration cfg) {
        long accessToken = Long.parseLong(cfg.jwtConfig.accessToken) * 60;

        return Handlers.routing()
                .get("", userSessionRequired(new GetMonitoredServices(), accessToken))
                .get("/service", userSessionRequired(new GetSingleMonitoredServiceHandler(), accessToken))
                .get("/logs/uptime", userSessionRequired(new GetUptimeLogsHandler(), accessToken))
                .get("/logs/ssl", userSessionRequired(new GetSSLLogsHandler(), accessToken))
                .get("/logs/audit", userSessionRequired(new GetAuditLogsHandler(), accessToken))
                .post("/create", userSessionRequired(new MonitoredServiceHandler(), accessToken))
                .put("/update", userSessionRequired(new UpdateMonitoredServiceHandler(), accessToken))
                .setInvalidMethodHandler(new Dispatcher(new InvalidMethod()))
                .setFallbackHandler(new Dispatcher(new FallBack()));
    }

    public static RoutingHandler systemSettings(XmlConfiguration cfg) {
        long accessToken = Long.parseLong(cfg.jwtConfig.accessToken) * 60;

        return Handlers.routing()
                .post("/", userSessionRequired(new SystemSettingsHandlers(), accessToken))
                .post("/rollback", userSessionRequired(new SystemSettingsHandlers(), accessToken))
                .get("/", userSessionRequired(new GetActiveSystemSettingsHandler(), accessToken))
                .get("/companies", userSessionRequired(new ListCompanies(), accessToken))
                .setInvalidMethodHandler(new Dispatcher(new InvalidMethod()))
                .setFallbackHandler(new Dispatcher(new FallBack()));
    }

    public static RoutingHandler generateReports(XmlConfiguration cfg) {
        long accessToken = Long.parseLong(cfg.jwtConfig.accessToken) * 60;

        return Handlers.routing()
                .get("/pdf/uptime", userSessionRequired(new GenerateUptimePdfReports(), accessToken))
                .get("/pdf/ssl", userSessionRequired(new GenerateSslPdfReports(), accessToken))
                .setInvalidMethodHandler(new Dispatcher(new InvalidMethod()))
                .setFallbackHandler(new Dispatcher(new FallBack()));
    }

    public static RoutingHandler serverSentEvents() throws Exception {

        SseHealthCheckHandler sseHealthCheckHandler = new SseHealthCheckHandler();
        SseServiceStatusHandler sseServiceStatusHandler = new SseServiceStatusHandler();

        return Handlers.routing()
                .get("/health", publicRoute(sseHealthCheckHandler.getHandler()))
                .get("/service-status", publicRoute(sseServiceStatusHandler.getHandler()))
                .setInvalidMethodHandler(new Dispatcher(new InvalidMethod()))
                .setFallbackHandler(new Dispatcher(new FallBack()));
    }
}
