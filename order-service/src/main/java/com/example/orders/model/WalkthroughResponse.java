package com.example.orders.model;

import java.util.List;

public record WalkthroughResponse(
        List<DebugStep> steps,
        OrderFulfillmentResponse result
) {
}
