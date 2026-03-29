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

package io.github.marianciuc.streamingservice.subscription.jobs;

import io.github.marianciuc.streamingservice.subscription.entity.SubscriptionStatus;
import io.github.marianciuc.streamingservice.subscription.entity.UserSubscriptions;
import io.github.marianciuc.streamingservice.subscription.service.impl.UserSubscriptionServiceImpl;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.UUID;

import static org.mockito.Mockito.*;

public class FetchActiveSubscriptionsJobTest {

    @SneakyThrows
    @Test
    public void testExecute() throws JobExecutionException {
        JobExecutionContext jobExecutionContext = mock(JobExecutionContext.class);
        UserSubscriptionServiceImpl service = mock(UserSubscriptionServiceImpl.class);
        FetchActiveSubscriptionsJob aSJob = new FetchActiveSubscriptionsJob(service);

        UserSubscriptions sub1 = new UserSubscriptions();
        UserSubscriptions sub2 = new UserSubscriptions();
        sub1.setId(UUID.randomUUID());
        sub2.setId(UUID.randomUUID());

        when(service.getAllUserSubscriptionsByStatusAndEndDate(any(), any())).thenReturn(Arrays.asList(sub1, sub2));

        aSJob.execute(jobExecutionContext);

        verify(service, times(1)).extendSubscription(sub1);
        verify(service, times(1)).extendSubscription(sub2);
        verify(service, times(1)).getAllUserSubscriptionsByStatusAndEndDate(SubscriptionStatus.ACTIVE, LocalDate.now());
    }
}
