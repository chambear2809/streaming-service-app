package io.github.marianciuc.streamingservice.billing.service.impl;

import io.github.marianciuc.streamingservice.billing.dto.request.CreateBillingInvoiceRequest;
import io.github.marianciuc.streamingservice.billing.dto.request.CreateBillingLineItemRequest;
import io.github.marianciuc.streamingservice.billing.dto.request.UpdateBillingInvoiceStatusRequest;
import io.github.marianciuc.streamingservice.billing.dto.response.BillingAccountSummaryResponse;
import io.github.marianciuc.streamingservice.billing.dto.response.BillingInvoiceResponse;
import io.github.marianciuc.streamingservice.billing.entity.BillingInvoice;
import io.github.marianciuc.streamingservice.billing.enums.BillingCurrency;
import io.github.marianciuc.streamingservice.billing.enums.BillingCycle;
import io.github.marianciuc.streamingservice.billing.enums.InvoiceStatus;
import io.github.marianciuc.streamingservice.billing.exception.InvalidBillingStateException;
import io.github.marianciuc.streamingservice.billing.repository.BillingBusinessEventRepository;
import io.github.marianciuc.streamingservice.billing.repository.BillingInvoiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BillingServiceImplTest {

    @Mock
    private BillingInvoiceRepository billingInvoiceRepository;

    @Mock
    private BillingBusinessEventRepository billingBusinessEventRepository;

    private BillingServiceImpl billingService;

    @BeforeEach
    void setUp() {
        billingService = new BillingServiceImpl(billingInvoiceRepository, billingBusinessEventRepository);
    }

    @Test
    void createInvoiceCalculatesTotalsAndNormalizesText() {
        when(billingInvoiceRepository.existsByInvoiceNumber(anyString())).thenReturn(false);
        doAnswer(invocation -> invocation.getArgument(0)).when(billingInvoiceRepository).save(any(BillingInvoice.class));

        CreateBillingInvoiceRequest request = new CreateBillingInvoiceRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                BillingCycle.MONTHLY,
                BillingCurrency.USD,
                LocalDate.now(),
                LocalDate.now().plusDays(14),
                LocalDate.now().minusDays(30),
                LocalDate.now().minusDays(1),
                new BigDecimal("1.50"),
                new BigDecimal("2.00"),
                "  studio invoice  ",
                List.of(
                        new CreateBillingLineItemRequest("  Channel carriage  ", "  primary feed  ", 2, new BigDecimal("12.50"), null, null),
                        new CreateBillingLineItemRequest("Ad insertion", null, 1, new BigDecimal("4.00"), null, null)
                )
        );

        BillingInvoiceResponse response = billingService.createInvoice(request);

        assertThat(response.invoiceNumber()).startsWith("BILL-");
        assertThat(response.subtotalAmount()).isEqualByComparingTo("29.00");
        assertThat(response.taxAmount()).isEqualByComparingTo("1.50");
        assertThat(response.discountAmount()).isEqualByComparingTo("2.00");
        assertThat(response.totalAmount()).isEqualByComparingTo("28.50");
        assertThat(response.balanceDue()).isEqualByComparingTo("28.50");
        assertThat(response.notes()).isEqualTo("studio invoice");
        assertThat(response.lineItems()).hasSize(2);
        assertThat(response.lineItems().get(0).title()).isEqualTo("Channel carriage");
        assertThat(response.lineItems().get(0).description()).isEqualTo("primary feed");
    }

    @Test
    void accountSummaryTreatsOpenPastInvoicesAsPastDue() {
        UUID userId = UUID.randomUUID();
        when(billingInvoiceRepository.findAllDetailedByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(
                invoice(userId, InvoiceStatus.OPEN, LocalDate.now().minusDays(3), "42.50"),
                invoice(userId, InvoiceStatus.PAID, LocalDate.now().minusDays(1), "15.00"),
                invoice(userId, InvoiceStatus.OPEN, LocalDate.now().plusDays(5), "10.00")
        ));

        BillingAccountSummaryResponse response = billingService.getAccountSummary(userId);

        assertThat(response.invoiceCount()).isEqualTo(3);
        assertThat(response.openInvoiceCount()).isEqualTo(1);
        assertThat(response.pastDueInvoiceCount()).isEqualTo(1);
        assertThat(response.paidInvoiceCount()).isEqualTo(1);
        assertThat(response.totalInvoiced()).isEqualByComparingTo("67.50");
        assertThat(response.totalPaid()).isEqualByComparingTo("15.00");
        assertThat(response.totalOutstanding()).isEqualByComparingTo("52.50");
        assertThat(response.nextDueDate()).isEqualTo(LocalDate.now().minusDays(3));
    }

    @Test
    void updateInvoiceStatusRejectsManualPastDueTransition() {
        UUID invoiceId = UUID.randomUUID();
        when(billingInvoiceRepository.findDetailedById(invoiceId)).thenReturn(Optional.of(invoice(
                UUID.randomUUID(),
                InvoiceStatus.OPEN,
                LocalDate.now().plusDays(2),
                "18.00"
        )));

        assertThatThrownBy(() -> billingService.updateInvoiceStatus(
                invoiceId,
                new UpdateBillingInvoiceStatusRequest(InvoiceStatus.PAST_DUE, null, null)
        )).isInstanceOf(InvalidBillingStateException.class)
                .hasMessageContaining("derived");
    }

    @Test
    void createInvoiceRejectsDueDateBeforeIssuedDate() {
        CreateBillingInvoiceRequest request = new CreateBillingInvoiceRequest(
                UUID.randomUUID(),
                null,
                null,
                BillingCycle.ONE_TIME,
                BillingCurrency.USD,
                LocalDate.now(),
                LocalDate.now().minusDays(1),
                null,
                null,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                List.of(new CreateBillingLineItemRequest("line", null, 1, new BigDecimal("1.00"), null, null))
        );

        assertThatThrownBy(() -> billingService.createInvoice(request))
                .isInstanceOf(InvalidBillingStateException.class)
                .hasMessageContaining("due date");
    }

    private BillingInvoice invoice(UUID userId, InvoiceStatus status, LocalDate dueDate, String total) {
        BigDecimal amount = new BigDecimal(total);
        return BillingInvoice.builder()
                .id(UUID.randomUUID())
                .invoiceNumber("BILL-TEST")
                .userId(userId)
                .status(status)
                .billingCycle(BillingCycle.MONTHLY)
                .currency(BillingCurrency.USD)
                .subtotalAmount(amount)
                .taxAmount(BigDecimal.ZERO.setScale(2))
                .discountAmount(BigDecimal.ZERO.setScale(2))
                .totalAmount(amount)
                .balanceDue(status == InvoiceStatus.PAID ? BigDecimal.ZERO.setScale(2) : amount)
                .issuedDate(dueDate.minusDays(7))
                .dueDate(dueDate)
                .notes(null)
                .createdAt(LocalDateTime.now().minusDays(2))
                .updatedAt(LocalDateTime.now().minusDays(1))
                .lineItems(List.of())
                .build();
    }
}
