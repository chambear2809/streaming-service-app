/*
 * Copyright (c) 2024  Vladimir Marianciuc. All Rights Reserved.
 *
 * Project: STREAMING SERVICE APP
 * File: SubscriptionClient.java
 *
 */

package io.github.marianciuc.streamingservice.order.client;

import feign.Headers;
import io.github.marianciuc.streamingservice.order.config.FeignConfiguration;
import io.github.marianciuc.streamingservice.order.dto.OrderActivationRequest;
import io.github.marianciuc.streamingservice.order.dto.SubscriptionDto;
import io.github.marianciuc.streamingservice.order.dto.UserSubscriptionDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

@FeignClient(name = "subscription-service-client", url = "${subscription.service.url:http://localhost:8080}", configuration = FeignConfiguration.class)
@Headers({
        "Accept: application/json",
        "Content-Type: application/json"
})
public interface SubscriptionClient {

    @RequestMapping(method = RequestMethod.GET, path = "/api/v1/subscription/{id}")
    ResponseEntity<SubscriptionDto> getSubscription(@PathVariable("id") UUID id);

    @RequestMapping(method = RequestMethod.GET, path = "/api/v1/subscription/active")
    ResponseEntity<UserSubscriptionDto> getActiveSubscription(@RequestParam("id") UUID uuid);

    @RequestMapping(method = RequestMethod.POST, path = "/api/v1/subscription/cancel")
    ResponseEntity<Void> cancelSubscription(@RequestParam("id") UUID uuid);

    @RequestMapping(method = RequestMethod.POST, path = "/api/v1/subscription/activate-order")
    ResponseEntity<Void> activateOrder(@RequestBody OrderActivationRequest request);
}
