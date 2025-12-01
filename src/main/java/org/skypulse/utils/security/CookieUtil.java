package org.skypulse.utils.security;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import java.util.UUID;

public final class CookieUtil {

    private CookieUtil() {}


    public static void setAccessTokenCookie(HttpServerExchange exchange,
                                            UUID jwtId,
                                            UUID userUuid,
                                            String email,
                                            String roleName,
                                            long accessTokenTtlSeconds,
                                            boolean isSecure) {

        String newAccess = JwtUtil.generateAccessTokenWithJti(
                userUuid.toString(),
                email,
                roleName,
                accessTokenTtlSeconds,
                jwtId
        );

        StringBuilder sb = new StringBuilder();
        sb.append("accessToken=").append(newAccess)
                .append("; Path=/; HttpOnly; Max-Age=").append(accessTokenTtlSeconds).append(";");
        sb.append(" SameSite=None;");
        if (isSecure) sb.append(" Secure;");

        exchange.getResponseHeaders().add(Headers.SET_COOKIE, sb.toString());
    }


    public static void setRefreshTokenCookie(HttpServerExchange exchange,
                                             String refreshToken,
                                             long refreshTokenTtlSeconds,
                                             boolean isSecure) {

        StringBuilder sb = new StringBuilder();
        sb.append("refreshToken=").append(refreshToken)
                .append("; Path=/; HttpOnly; Max-Age=").append(refreshTokenTtlSeconds).append(";");
        sb.append(" SameSite=None;");
        if (isSecure) sb.append(" Secure;");

        exchange.getResponseHeaders().add(Headers.SET_COOKIE, sb.toString());
    }
}
