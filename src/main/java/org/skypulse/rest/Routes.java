package org.skypulse.rest;

import io.undertow.Handlers;
import io.undertow.server.RoutingHandler;
import org.skypulse.config.utils.XmlConfiguration;
import org.skypulse.handlers.HealthCheckHandler;
import org.skypulse.handlers.TaskController;
import org.skypulse.handlers.auth.*;
import org.skypulse.handlers.company.ListCompanies;
import org.skypulse.handlers.contacts.*;
import org.skypulse.handlers.logs.GetAuditLogsHandler;
import org.skypulse.handlers.logs.GetSSLLogsHandler;
import org.skypulse.handlers.logs.GetUptimeLogsHandler;
import org.skypulse.handlers.reports.GenerateUptimePdfReports;
import org.skypulse.handlers.reports.GenerateSslPdfReports;
import org.skypulse.handlers.services.GetMonitoredServices;
import org.skypulse.handlers.services.GetSingleMonitoredServiceHandler;
import org.skypulse.handlers.services.MonitoredServiceHandler;
import org.skypulse.handlers.services.UpdateMonitoredServiceHandler;
import org.skypulse.handlers.settings.GetActiveSystemSettingsHandler;
import org.skypulse.handlers.settings.SystemSettingsHandlers;
import org.skypulse.handlers.users.CreateNewUser;
import org.skypulse.handlers.users.GetUserDetailHandler;
import org.skypulse.handlers.users.GetUsersHandler;
import org.skypulse.rest.base.Dispatcher;
import org.skypulse.rest.base.FallBack;
import org.skypulse.rest.base.InvalidMethod;
import org.skypulse.services.sse.SseHealthCheckHandler;
import org.skypulse.services.sse.SseServiceStatusHandler;

import static org.skypulse.Main.appScheduler;
import static org.skypulse.rest.auth.HandlerFactory.build;
import static org.skypulse.rest.base.RouteUtils.open;
import static org.skypulse.rest.base.RouteUtils.secure;

public class Routes {

    public static RoutingHandler auth(XmlConfiguration cfg) {
        long accessToken = Long.parseLong(cfg.jwtConfig.accessToken) * 60;

        return Handlers.routing()
                .post("/login", open(new UserLoginHandler(cfg)))
                .post("/register", open(new UserSignupHandler()))
                .get("/profile", secure(new GetUserProfileHandler(), accessToken))
                .post("/logout", secure(new LogoutHandler(), accessToken))
                .post("/activate", open(new ActivateUserHandler()))
                .post("/request/reset-password", open(new RequestResetPasswordHandler()))
                .post("/reset-password", open(new ResetPasswordHandler()))
                .setInvalidMethodHandler(new Dispatcher(new InvalidMethod()))
                .setFallbackHandler(new Dispatcher(new FallBack()));
    }

    public static RoutingHandler health(XmlConfiguration cfg) {
        long accessToken = Long.parseLong(cfg.jwtConfig.accessToken) * 60;

        return Handlers.routing()
                .get("/health", open(new HealthCheckHandler()))
                .get("/tasks/reload", secure(build(new TaskController(appScheduler)), accessToken))
                .setInvalidMethodHandler(new Dispatcher(new InvalidMethod()))
                .setFallbackHandler(new Dispatcher(new FallBack()));
    }  public static RoutingHandler users(XmlConfiguration cfg) {
        long accessToken = Long.parseLong(cfg.jwtConfig.accessToken) * 60;

        return Handlers.routing()
                .get("", secure(new GetUsersHandler(), accessToken))
                .get("/user", secure(new GetUserDetailHandler(), accessToken))
                .post("/user/create", secure(new CreateNewUser(), accessToken))
                .setInvalidMethodHandler(new Dispatcher(new InvalidMethod()))
                .setFallbackHandler(new Dispatcher(new FallBack()));
    }


    public static RoutingHandler contactGroups(XmlConfiguration cfg) {
        long accessToken = Long.parseLong(cfg.jwtConfig.accessToken) * 60;

        return Handlers.routing()

                .post("/groups", secure(new CreateContactGroupHandler(), accessToken))
                .post("/groups/{uuid}/members",secure(new AddMembersToGroupHandler(), accessToken))
                .post("/groups/{uuid}/services",secure(new AddServicesToGroupHandler(), accessToken))
                .post("/groups/members/{uuid}", secure(new GetSingleMonitoredServiceHandler(), accessToken))
                .get("/groups" , secure(new GetContactGroupsHandler(), accessToken))
                .get("/group" , secure(new GetSingleContactGroupHandler(), accessToken))
                .setInvalidMethodHandler(new Dispatcher(new InvalidMethod()))
                .setFallbackHandler(new Dispatcher(new FallBack()));
    }


    public static RoutingHandler monitoredServices(XmlConfiguration cfg) {
        long accessToken = Long.parseLong(cfg.jwtConfig.accessToken) * 60;

        return Handlers.routing()
                .get("", secure(new GetMonitoredServices(), accessToken))
                .get("/service", secure(new GetSingleMonitoredServiceHandler(), accessToken))
                .get("/logs/uptime", secure(new GetUptimeLogsHandler(), accessToken))
                .get("/logs/ssl", secure(new GetSSLLogsHandler(), accessToken))
                .get("/logs/audit", secure(new GetAuditLogsHandler(), accessToken))
                .post("/create", secure(new MonitoredServiceHandler(), accessToken))
                .put("/update", secure(new UpdateMonitoredServiceHandler(), accessToken))
                .setInvalidMethodHandler(new Dispatcher(new InvalidMethod()))
                .setFallbackHandler(new Dispatcher(new FallBack()));
    }


    public static RoutingHandler systemSettings(XmlConfiguration cfg) {
        long accessToken = Long.parseLong(cfg.jwtConfig.accessToken) * 60;

        return Handlers.routing()
                .post("/", secure(new SystemSettingsHandlers(), accessToken))
                .post("/rollback", secure(new SystemSettingsHandlers(), accessToken))
                .get("/", secure(new GetActiveSystemSettingsHandler(), accessToken))
                .get("/companies", secure(new ListCompanies(), accessToken))
                .setInvalidMethodHandler(new Dispatcher(new InvalidMethod()))
                .setFallbackHandler(new Dispatcher(new FallBack()));
    }
    public static RoutingHandler generateReports(XmlConfiguration cfg) {
        long accessToken = Long.parseLong(cfg.jwtConfig.accessToken) * 60;

        return Handlers.routing()
                .get("/pdf/uptime", secure(new GenerateUptimePdfReports(), accessToken))
                .get("/pdf/ssl", secure(new GenerateSslPdfReports(), accessToken))
                .setInvalidMethodHandler(new Dispatcher(new InvalidMethod()))
                .setFallbackHandler(new Dispatcher(new FallBack()));
    }

    public static RoutingHandler serverSentEvents(XmlConfiguration cfg) throws Exception {
        long accessToken = Long.parseLong(cfg.jwtConfig.accessToken) * 60;

        SseHealthCheckHandler sseHealthCheckHandler = new SseHealthCheckHandler();
        SseServiceStatusHandler sseServiceStatusHandler = new SseServiceStatusHandler();

        return Handlers.routing()
                .get("/health", secure(sseHealthCheckHandler.getHandler(), accessToken))
                .get("/service-status", open(sseServiceStatusHandler.getHandler()))
                .setInvalidMethodHandler(new Dispatcher(new InvalidMethod()))
                .setFallbackHandler(new Dispatcher(new FallBack()));
    }

}
