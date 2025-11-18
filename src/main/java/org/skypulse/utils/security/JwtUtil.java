package org.skypulse.utils.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

public final class JwtUtil {

    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_ROLE = "role";

    private JwtUtil() {}

    /**
     * Generate an access token using the user's UUID as subject and a provided jti (UUID).
     *
     * @param userUuid user UUID (as string)
     * @param email user's email
     * @param roleName user's role name
     * @param ttlSeconds token lifetime in seconds
     * @param jti the JWT ID to set (should match auth_sessions.jwt_id)
     * @return compact JWT string
     */
    public static String generateAccessTokenWithJti(String userUuid, String email, String roleName, long ttlSeconds, UUID jti) {
        String jwtKey = KeyProvider.get("JWT_SIGNING_KEY");
        SecretKey key = Keys.hmacShaKeyFor(jwtKey.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();

        return Jwts.builder()
                .setSubject(userUuid)
                .setId(jti.toString())                // IMPORTANT: use provided jti
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(now.plusSeconds(ttlSeconds)))
                .addClaims(Map.of(
                        CLAIM_EMAIL, email,
                        CLAIM_ROLE, roleName
                ))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    private static Claims parseToken(String token) {
        String jwtKey = KeyProvider.get("JWT_SIGNING_KEY");
        SecretKey key = Keys.hmacShaKeyFor(jwtKey.getBytes(StandardCharsets.UTF_8));
        return Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    public static String getUserId(String token) {
        try {
            Claims c = parseToken(token);
            return c.getSubject();
        } catch (Exception e) {
            return null;
        }
    }

    public static String getEmail(String token) {
        try {
            Claims c = parseToken(token);
            return c.get(CLAIM_EMAIL, String.class);
        } catch (Exception e) {
            return null;
        }
    }

    public static String getRole(String token) {
        try {
            Claims c = parseToken(token);
            return c.get(CLAIM_ROLE, String.class);
        } catch (Exception e) {
            return null;
        }
    }

    public static String getJwtId(String token) {
        try {
            Claims c = parseToken(token);
            return c.getId();
        } catch (Exception e) {
            return null;
        }
    }
}
