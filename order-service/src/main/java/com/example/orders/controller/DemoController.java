package com.example.orders.controller;

import com.example.orders.model.WalkthroughResponse;
import com.example.orders.service.OrderNotFoundException;
import com.example.orders.service.WalkthroughService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private final WalkthroughService walkthroughService;

    public DemoController(WalkthroughService walkthroughService) {
        this.walkthroughService = walkthroughService;
    }

    @GetMapping("/walkthrough/{orderId}")
    public WalkthroughResponse walkthrough(
            @PathVariable String orderId,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        String accessToken = extractBearerToken(request);
        String walkthroughUrl = request.getRequestURL().toString();
        return walkthroughService.walkthrough(orderId, jwt, accessToken, walkthroughUrl);
    }

    @ExceptionHandler(OrderNotFoundException.class)
    ProblemDetail handleNotFound(OrderNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail handleTokenExchangeFailure(IllegalStateException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, ex.getMessage());
    }

    private String extractBearerToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new IllegalArgumentException("Missing Bearer token");
        }
        return authorization.substring("Bearer ".length());
    }
}
