package com.example.orders.service;

import com.example.orders.config.TokenExchangeProperties;
import com.example.orders.model.HttpTrace;
import com.example.orders.model.TokenExchangeResult;
import com.example.orders.util.JwtDebugUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class TokenExchangeService {

    private static final Logger log = LoggerFactory.getLogger(TokenExchangeService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String TOKEN_EXCHANGE_GRANT =
            "urn:ietf:params:oauth:grant-type:token-exchange";
    private static final String ACCESS_TOKEN_TYPE =
            "urn:ietf:params:oauth:token-type:access_token";

    private final TokenExchangeProperties properties;
    private final WebClient webClient;

    public TokenExchangeService(TokenExchangeProperties properties, WebClient.Builder webClientBuilder) {
        this.properties = properties;
        this.webClient = webClientBuilder.build();
    }

    public String exchangeForWarehouseToken(String customerAccessToken) {
        return exchangeWithTrace(customerAccessToken).accessToken();
    }

    public TokenExchangeResult exchangeWithTrace(String customerAccessToken) {
        MultiValueMap<String, String> form = buildExchangeForm(customerAccessToken);
        String requestBody = formToDebugString(form);

        HttpTrace requestTrace = new HttpTrace(
                "POST",
                properties.tokenUrl(),
                Map.of("Content-Type", "application/x-www-form-urlencoded"),
                requestBody
        );

        long started = System.currentTimeMillis();
        log.info("Exchanging customer token at {} for warehouse audience {}",
                properties.tokenUrl(), properties.audience());

        JsonNode response = webClient.post()
                .uri(properties.tokenUrl())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        long durationMs = System.currentTimeMillis() - started;

        if (response == null || !response.hasNonNull("access_token")) {
            throw new IllegalStateException("Token exchange failed: empty response from warehouse IdP");
        }

        String accessToken = response.get("access_token").asText();
        HttpTrace responseTrace = new HttpTrace(
                "POST",
                properties.tokenUrl(),
                Map.of("Content-Type", "application/json"),
                sanitizeTokenResponse(response)
        );

        log.info("Token exchange succeeded for audience {}", properties.audience());
        return new TokenExchangeResult(
                accessToken,
                requestTrace,
                responseTrace,
                JwtDebugUtil.decodePayload(accessToken),
                durationMs
        );
    }

    private MultiValueMap<String, String> buildExchangeForm(String customerAccessToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", TOKEN_EXCHANGE_GRANT);
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("subject_token", customerAccessToken);
        form.add("subject_token_type", ACCESS_TOKEN_TYPE);
        form.add("subject_issuer", properties.subjectIssuer());
        form.add("requested_token_type", ACCESS_TOKEN_TYPE);
        form.add("audience", properties.audience());
        return form;
    }

    private String formToDebugString(MultiValueMap<String, String> form) {
        return form.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream().map(value -> {
                    if ("client_secret".equals(entry.getKey())) {
                        return entry.getKey() + "=***REDACTED***";
                    }
                    if ("subject_token".equals(entry.getKey())) {
                        return entry.getKey() + "=" + JwtDebugUtil.preview(value);
                    }
                    return entry.getKey() + "=" + value;
                }))
                .collect(Collectors.joining("&"));
    }

    private String sanitizeTokenResponse(JsonNode response) {
        try {
            ObjectNode copy = response.deepCopy();
            if (copy.hasNonNull("access_token")) {
                copy.put("access_token", JwtDebugUtil.preview(copy.get("access_token").asText()));
            }
            if (copy.hasNonNull("refresh_token")) {
                copy.put("refresh_token", JwtDebugUtil.preview(copy.get("refresh_token").asText()));
            }
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(copy);
        } catch (Exception ex) {
            return response.toString();
        }
    }
}
