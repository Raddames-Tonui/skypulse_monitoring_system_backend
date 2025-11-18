package org.skypulse.utils.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.SignatureException;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

public class JwtUtil {

    public static String generateAccessTokenWithJti(String user_uuid, String email, String roleName, long ttlSeconds, UUID jti){
        String jwt_key = KeyProvider.get("JWT_SIGNING_KEY");
        SecretKey key = Keys.hmacShaKeyFor(jwt_key.getBytes());
        Instant now = Instant.now();
        return Jwts.builder()
                .setSubject(user_uuid)
                .setId(jti.toString())
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(now.plusSeconds(ttlSeconds)))
                .addClaims(Map.of(
                        "email", email,
                        "role", roleName
                ))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }


    //  Parse token and get claims
    private static Claims parseToken(String token) throws SignatureException {
        String jwt_key = KeyProvider.get("JWT_SIGNING_KEY");
        SecretKey key = Keys.hmacShaKeyFor(jwt_key.getBytes(StandardCharsets.UTF_8));
        return Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    //  Get user UUID
    public static String getUserId(String token) {
        try {
            Claims claims = parseToken(token);
            return claims.getSubject();
        } catch (Exception e) {
            return null;
        }
    }

    //  Get email from token
    public static String getEmail(String token) {
        try {
            Claims claims = parseToken(token);
            return claims.get("email", String.class);
        } catch (Exception e) {
            return null;
        }
    }

    //  Get role from token
    public static String getRole(String token) {
        try {
            Claims claims = parseToken(token);
            return claims.get("role", String.class);
        } catch (Exception e) {
            return null;
        }
    }

    public static String getJwtId(String token) {
        try {
            Claims claims = parseToken(token);
            return claims.getId();
        } catch (Exception e) {
            return null;
        }
    }
}