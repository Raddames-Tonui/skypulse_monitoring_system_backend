package org.skypulse.utils.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

public class JwtUtil {

    public static String generateAccessToken(String userId, String email, String roleName, long ttlSeconds){
        String jwt_key = KeyProvider.get("JWT_SIGNING_KEY");
        SecretKey key = Keys.hmacShaKeyFor(jwt_key.getBytes());
        Instant now = Instant.now();
        return Jwts.builder()
                .setSubject(userId)
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(now.plusSeconds(ttlSeconds)))
                .addClaims(Map.of(
                        "email", email,
                        "role", roleName
                ))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }
}
