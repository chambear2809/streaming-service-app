package io.github.marianciuc.streamingservice.billing.config;

import io.github.marianciuc.streamingservice.billing.dto.request.CreateBillingBusinessEventRequest;
import io.github.marianciuc.streamingservice.billing.enums.BillingBusinessEventType;
import io.github.marianciuc.streamingservice.billing.enums.BillingCurrency;
import io.github.marianciuc.streamingservice.billing.enums.BillingCycle;
import io.github.marianciuc.streamingservice.billing.repository.BillingBusinessEventRepository;
import io.github.marianciuc.streamingservice.billing.repository.BillingInvoiceRepository;
import io.github.marianciuc.streamingservice.billing.service.BillingService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Configuration
@Profile("broadcast-demo")
public class DemoBillingBootstrap {

    @Bean
    CommandLineRunner bootstrapDemoBillingEvents(
            BillingService billingService,
            BillingInvoiceRepository invoiceRepository,
            BillingBusinessEventRepository eventRepository
    ) {
        return args -> {
            if (eventRepository.count() > 0 || invoiceRepository.count() > 0) {
                return;
            }

            billingService.recordBusinessEvent(new CreateBillingBusinessEventRequest(
                    UUID.fromString("40000000-0000-0000-0000-000000000001"),
                    BillingBusinessEventType.ORDER_BOOKED,
                    UUID.fromString("8f61f6c0-29dc-4f6d-9a31-1fd4a4ad5001"),
                    null,
                    UUID.fromString("50000000-0000-0000-0000-000000000001"),
                    null,
                    BillingCycle.MONTHLY,
                    BillingCurrency.USD,
                    "Regional carriage package",
                    "Prime East and replay rights for March operations.",
                    1,
                    new BigDecimal("4200.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    LocalDate.now().minusDays(2),
                    LocalDate.now().plusDays(12),
                    LocalDate.now().minusDays(30),
                    LocalDate.now().minusDays(1),
                    null,
                    "Regional carriage renewal for North Coast Sports Network."
            ));

            billingService.recordBusinessEvent(new CreateBillingBusinessEventRequest(
                    UUID.fromString("40000000-0000-0000-0000-000000000002"),
                    BillingBusinessEventType.SUBSCRIPTION_RENEWED,
                    UUID.fromString("8f61f6c0-29dc-4f6d-9a31-1fd4a4ad5002"),
                    null,
                    null,
                    UUID.fromString("60000000-0000-0000-0000-000000000001"),
                    BillingCycle.MONTHLY,
                    BillingCurrency.USD,
                    "Forecast service subscription",
                    "Metro Weather Desk continuous forecast feed.",
                    1,
                    new BigDecimal("1850.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    LocalDate.now().minusDays(18),
                    LocalDate.now().minusDays(4),
                    LocalDate.now().minusDays(45),
                    LocalDate.now().minusDays(15),
                    null,
                    "Always-on forecast service settled by ACH."
            ));

            UUID paidInvoiceId = invoiceRepository.findAllDetailedBySubscriptionIdOrderByCreatedAtDesc(
                    UUID.fromString("60000000-0000-0000-0000-000000000001")
            ).stream().findFirst().map(invoice -> invoice.getId()).orElse(null);

            if (paidInvoiceId != null) {
                billingService.recordBusinessEvent(new CreateBillingBusinessEventRequest(
                        UUID.fromString("40000000-0000-0000-0000-000000000003"),
                        BillingBusinessEventType.PAYMENT_CAPTURED,
                        UUID.fromString("8f61f6c0-29dc-4f6d-9a31-1fd4a4ad5002"),
                        paidInvoiceId,
                        null,
                        UUID.fromString("60000000-0000-0000-0000-000000000001"),
                        BillingCycle.MONTHLY,
                        BillingCurrency.USD,
                        "ACH settlement",
                        "ACH settlement cleared the current renewal.",
                        1,
                        new BigDecimal("1850.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        LocalDate.now().minusDays(18),
                        LocalDate.now().minusDays(4),
                        LocalDate.now().minusDays(45),
                        LocalDate.now().minusDays(15),
                        "ACH-SETTLED-" + LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE),
                        "Settlement applied to the forecast service renewal."
                ));
            }

            billingService.recordBusinessEvent(new CreateBillingBusinessEventRequest(
                    UUID.fromString("40000000-0000-0000-0000-000000000004"),
                    BillingBusinessEventType.ORDER_BOOKED,
                    UUID.fromString("8f61f6c0-29dc-4f6d-9a31-1fd4a4ad5004"),
                    null,
                    UUID.fromString("50000000-0000-0000-0000-000000000004"),
                    null,
                    BillingCycle.ONE_TIME,
                    BillingCurrency.USD,
                    "Event contribution window",
                    "Temporary live-event contribution circuit.",
                    1,
                    new BigDecimal("960.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    LocalDate.now().minusDays(16),
                    LocalDate.now().minusDays(1),
                    LocalDate.now().minusDays(16),
                    LocalDate.now().minusDays(1),
                    null,
                    "Pop-up event contribution window awaiting settlement."
            ));
        };
    }
}
