package org.skypulse.utils.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.skypulse.config.database.JdbcUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

public final class JwtUtil {

    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_ROLE = "role";

    private JwtUtil() {}

    /**
     *  Access token generated using the user's UUID as subject and a provided jti (UUID).
     *
     * @param userUuid user UUID (as string)
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
                .setId(jti.toString().toUpperCase())
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(now.plusSeconds(ttlSeconds)))
                .addClaims(Map.of(
                        CLAIM_EMAIL, email,
                        CLAIM_ROLE, roleName
                ))
                .signWith(key, SignatureAlgorithm.HS256)
                .setIssuer("SkyPulse-Monitoring-System")
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

    public static String getUserUUId(String token) {
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


        public static long getUserIdFromUuid(String userUuidStr) {
            try (Connection conn = JdbcUtils.getConnection()) {
                String sql = "SELECT user_id FROM users WHERE uuid = ? AND is_active = TRUE";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setObject(1, UUID.fromString(userUuidStr));
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return rs.getLong("user_id");
                        } else {
                            throw new IllegalArgumentException("User not found or inactive for UUID: " + userUuidStr);
                        }
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to get user_id for UUID: " + userUuidStr, e);
            }
        }

    public static boolean isExpired(String token) {
        try {
            Claims claims = parseToken(token);
            Date exp = claims.getExpiration();
            return exp == null || exp.toInstant().isBefore(Instant.now());
        } catch (Exception e) {
            return true;
        }
    }

}
