/*
 * Copyright (c) 2024 Vladimir Marianciuc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *   The above copyright notice and this permission notice shall be included in
 *    all copies or substantial portions of the Software.
 *
 *    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *    IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *    FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *     AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *     LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *     OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *      THE SOFTWARE.
 */

package io.github.marianciuc.streamingservice.subscription.service.impl;

import io.github.marianciuc.streamingservice.subscription.dto.SubscriptionRequest;
import io.github.marianciuc.streamingservice.subscription.dto.SubscriptionResponse;
import io.github.marianciuc.streamingservice.subscription.entity.RecordStatus;
import io.github.marianciuc.streamingservice.subscription.entity.Subscription;
import io.github.marianciuc.streamingservice.subscription.exceptions.NotFoundException;
import io.github.marianciuc.streamingservice.subscription.mapper.SubscriptionMapper;
import io.github.marianciuc.streamingservice.subscription.repository.SubscriptionRepository;
import io.github.marianciuc.streamingservice.subscription.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service class for managing subscriptions.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SubscriptionServiceImpl implements SubscriptionService {

    private final static String SUBSCRIPTION_NOT_FOUND_MSG = "Subscription not found";

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionMapper subscriptionMapper;


    public void deleteSubscription(UUID id) {
        Subscription subscription = getSubscription(id);
        subscription.setRecordStatus(RecordStatus.DELETED);
        subscriptionRepository.save(subscription);
    }

    public SubscriptionResponse createSubscription(SubscriptionRequest request) {
        Subscription subscription = subscriptionMapper.toEntity(request);
        return subscriptionMapper.toResponse(subscriptionRepository.save(subscription));
    }

    public SubscriptionResponse updateSubscription(UUID id, SubscriptionRequest request) {
        Subscription subscription = getSubscription(id);
        Subscription requestedState = subscriptionMapper.toEntity(request);
        if (!Objects.equals(subscription.getName(), request.name())) subscription.setName(request.name());
        if (!Objects.equals(subscription.getDescription(), request.description())) subscription.setDescription(request.description());
        if (!Objects.equals(subscription.getDurationInDays(), request.durationInDays())) subscription.setDurationInDays(request.durationInDays());
        if (!Objects.equals(subscription.getAllowedActiveSessions(), request.allowedActiveSessions())) subscription.setAllowedActiveSessions(request.allowedActiveSessions());
        if (!Objects.equals(subscription.getPrice(), request.price())) subscription.setPrice(request.price());
        if (!Objects.equals(subscription.getCurrency(), request.currency())) subscription.setCurrency(request.currency());
        if (!Objects.equals(subscription.getIsTemporary(), requestedState.getIsTemporary())) subscription.setIsTemporary(requestedState.getIsTemporary());
        if (!Objects.equals(subscription.getNextSubscription(), requestedState.getNextSubscription())) subscription.setNextSubscription(requestedState.getNextSubscription());
        if (!Objects.equals(subscription.getResolutions(), requestedState.getResolutions())) subscription.setResolutions(requestedState.getResolutions());
        // TODO send message to users about updates
        return subscriptionMapper.toResponse(subscriptionRepository.save(subscription));
    }


    public Subscription getSubscription(UUID id){
        return subscriptionRepository.findById(id).orElseThrow(() -> new NotFoundException(SUBSCRIPTION_NOT_FOUND_MSG));
    }

    public SubscriptionResponse getSubscriptionResponse(UUID id) {
        return subscriptionMapper.toResponse(getSubscription(id));
    }

    public List<SubscriptionResponse> getAllSubscriptions(){
        return subscriptionRepository.findAll().stream()
                .filter(subscription -> subscription.getRecordStatus() == RecordStatus.ACTIVE)
                .map(subscriptionMapper::toResponse)
                .collect(Collectors.toList());
    }
}
