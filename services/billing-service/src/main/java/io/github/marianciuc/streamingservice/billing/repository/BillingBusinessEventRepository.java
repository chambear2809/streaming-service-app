package io.github.marianciuc.streamingservice.billing.repository;

import io.github.marianciuc.streamingservice.billing.entity.BillingBusinessEvent;
import io.github.marianciuc.streamingservice.billing.enums.BillingBusinessEventType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BillingBusinessEventRepository extends JpaRepository<BillingBusinessEvent, UUID> {

    boolean existsByBusinessEventId(UUID businessEventId);

    Optional<BillingBusinessEvent> findByBusinessEventId(UUID businessEventId);

    List<BillingBusinessEvent> findAllByOrderByCreatedAtDesc();

    List<BillingBusinessEvent> findAllByUserIdOrderByCreatedAtDesc(UUID userId);

    List<BillingBusinessEvent> findAllByEventTypeOrderByCreatedAtDesc(BillingBusinessEventType eventType);
}
