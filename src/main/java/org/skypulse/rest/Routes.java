package org.skypulse.rest;

import io.undertow.Handlers;
import io.undertow.server.RoutingHandler;
import io.undertow.server.handlers.BlockingHandler;
import org.skypulse.handlers.auth.GetUserProfileHandler;
import org.skypulse.handlers.auth.LogoutHandler;
import org.skypulse.handlers.auth.UserLoginHandler;
import org.skypulse.handlers.auth.UserSignupHandler;
import org.skypulse.rest.base.Dispatcher;
import org.skypulse.rest.base.FallBack;
import org.skypulse.rest.base.InvalidMethod;
import org.skypulse.utils.security.AuthMiddleware;

public class Routes {

    public static RoutingHandler auth() {
        return Handlers.routing()
                .post("/register", new Dispatcher(new BlockingHandler(new UserSignupHandler())))
                .post("/login", new Dispatcher(new BlockingHandler(new UserLoginHandler())))
                .get("/profile", new Dispatcher( new BlockingHandler( new AuthMiddleware(new GetUserProfileHandler()))))
//                .get("/profile", new Dispatcher( new BlockingHandler( new GetUserProfileHandler())))
//                .post("/logout", new Dispatcher( new BlockingHandler( new AuthMiddleware(new LogoutHandler()))))
                .post("/logout", new Dispatcher( new BlockingHandler(new LogoutHandler())))
                .setInvalidMethodHandler(new Dispatcher(new InvalidMethod()))
                .setFallbackHandler(new Dispatcher(new FallBack()));
    }
}
