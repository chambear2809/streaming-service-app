package io.github.marianciuc.streamingservice.billing.repository;

import io.github.marianciuc.streamingservice.billing.entity.BillingInvoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BillingInvoiceRepository extends JpaRepository<BillingInvoice, UUID> {

    boolean existsByInvoiceNumber(String invoiceNumber);

    @Query("""
            select distinct invoice
            from BillingInvoice invoice
            left join fetch invoice.lineItems
            where invoice.id = :invoiceId
            """)
    Optional<BillingInvoice> findDetailedById(@Param("invoiceId") UUID invoiceId);

    @Query("""
            select distinct invoice
            from BillingInvoice invoice
            left join fetch invoice.lineItems
            order by invoice.createdAt desc
            """)
    List<BillingInvoice> findAllDetailedOrderByCreatedAtDesc();

    @Query("""
            select distinct invoice
            from BillingInvoice invoice
            left join fetch invoice.lineItems
            where invoice.userId = :userId
            order by invoice.createdAt desc
            """)
    List<BillingInvoice> findAllDetailedByUserIdOrderByCreatedAtDesc(@Param("userId") UUID userId);
}
