package io.github.marianciuc.streamingservice.user.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface UserOutboxEventRepository extends JpaRepository<UserOutboxEvent, UUID> {

    @Query(value = """
            select *
              from user_outbox_events
             where published_at is null
               and next_attempt_at <= :nextAttemptAt
             order by created_at asc
             limit :limit
             for update skip locked
            """, nativeQuery = true)
    List<UserOutboxEvent> lockPendingEventsForPublish(
            @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
            @Param("limit") int limit
    );

    long countByPublishedAtIsNull();

    long countByPublishedAtIsNullAndNextAttemptAtLessThanEqual(LocalDateTime nextAttemptAt);
}
