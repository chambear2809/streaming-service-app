package io.github.marianciuc.streamingservice.subscription.jobs;

import io.github.marianciuc.streamingservice.subscription.entity.SubscriptionStatus;
import io.github.marianciuc.streamingservice.subscription.entity.UserSubscriptions;
import io.github.marianciuc.streamingservice.subscription.service.impl.UserSubscriptionServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.stereotype.Component;

import javax.naming.OperationNotSupportedException;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class FetchActiveSubscriptionsJob implements Job {

    private final UserSubscriptionServiceImpl service;

    @Override
    public void execute(JobExecutionContext jobExecutionContext) throws JobExecutionException {
        LocalDate now = LocalDate.now();
        JobExecutionException failure = null;

        List<UserSubscriptions> renewalsReadyToFinalize = service.getRenewalsReadyToFinalize();
        for (UserSubscriptions subscription : renewalsReadyToFinalize) {
            try {
                service.extendSubscription(subscription);
            } catch (IOException | OperationNotSupportedException | RuntimeException exception) {
                log.error("Failed to finalize renewal for subscription {}", subscription.getId(), exception);
                if (failure == null) {
                    failure = new JobExecutionException("Failed to extend one or more subscriptions", exception);
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }

        List<UserSubscriptions> activeSubscriptions = service.getAllUserSubscriptionsByStatusEndingOnOrBefore(SubscriptionStatus.ACTIVE, now);
        for (UserSubscriptions subscription : activeSubscriptions) {
            try {
                service.extendSubscription(subscription);
            } catch (IOException | OperationNotSupportedException | RuntimeException exception) {
                log.error("Failed to extend subscription {}", subscription.getId(), exception);
                if (failure == null) {
                    failure = new JobExecutionException("Failed to extend one or more subscriptions", exception);
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }

        if (failure != null) {
            throw failure;
        }
    }
}
