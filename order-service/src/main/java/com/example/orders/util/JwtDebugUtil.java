package com.example.orders.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.oauth2.jwt.Jwt;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

public final class JwtDebugUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JwtDebugUtil() {
    }

    public static Map<String, Object> claimsFromJwt(Jwt jwt) {
        Map<String, Object> claims = new LinkedHashMap<>();
        jwt.getClaims().forEach(claims::put);
        return claims;
    }

    public static Map<String, Object> decodePayload(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                return Map.of("error", "Not a JWT");
            }
            byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
            return MAPPER.readValue(new String(decoded, StandardCharsets.UTF_8), new TypeReference<>() {
            });
        } catch (Exception ex) {
            return Map.of("error", "Unable to decode JWT payload");
        }
    }

    public static String preview(String token) {
        if (token == null || token.isBlank()) {
            return "";
        }
        if (token.length() <= 24) {
            return token;
        }
        return token.substring(0, 12) + "…" + token.substring(token.length() - 8);
    }
}
