package io.github.marianciuc.streamingservice.subscription.repository;

import io.github.marianciuc.streamingservice.subscription.entity.SubscriptionStatus;
import io.github.marianciuc.streamingservice.subscription.entity.UserSubscriptions;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserSubscriptionRepository extends JpaRepository<UserSubscriptions, UUID> {
    List<UserSubscriptions> findAllByStatusAndEndDate(SubscriptionStatus status, LocalDate endDate);

    @Query("""
            select subscription
              from UserSubscriptions subscription
             where subscription.status = :status
               and subscription.endDate <= :endDate
               and subscription.renewalOrderId is null
               and (
                    subscription.renewalClaimedAt is null
                    or subscription.renewalClaimedAt <= :claimExpiredBefore
               )
             order by subscription.endDate asc
            """)
    List<UserSubscriptions> findAllEligibleForRenewal(
            @Param("status") SubscriptionStatus status,
            @Param("endDate") LocalDate endDate,
            @Param("claimExpiredBefore") LocalDateTime claimExpiredBefore
    );

    @Query("""
            select subscription
              from UserSubscriptions subscription
             where subscription.status = :status
               and subscription.renewalOrderId is not null
             order by subscription.renewalClaimedAt asc
            """)
    List<UserSubscriptions> findAllPendingRenewalFinalization(@Param("status") SubscriptionStatus status);

    Optional<UserSubscriptions> findByOrderId(UUID uuid);

    Optional<UserSubscriptions> findFirstByUserId(UUID id);

    List<UserSubscriptions> findAllByUserIdAndStatusOrderByEndDateDesc(UUID id, SubscriptionStatus status);

    Optional<UserSubscriptions> findFirstByUserIdAndStatusOrderByEndDateDesc(UUID id, SubscriptionStatus status);

    @Modifying
    @Query("""
            update UserSubscriptions subscription
               set subscription.renewalClaimedAt = :claimedAt,
                   subscription.renewalOrderId = null
             where subscription.id = :subscriptionId
               and subscription.status = :status
               and subscription.renewalOrderId is null
               and (
                    subscription.renewalClaimedAt is null
                    or subscription.renewalClaimedAt <= :claimExpiredBefore
               )
            """)
    int claimRenewal(
            @Param("subscriptionId") UUID subscriptionId,
            @Param("status") SubscriptionStatus status,
            @Param("claimedAt") LocalDateTime claimedAt,
            @Param("claimExpiredBefore") LocalDateTime claimExpiredBefore
    );

    @Modifying
    @Query("""
            update UserSubscriptions subscription
               set subscription.renewalOrderId = :renewalOrderId
             where subscription.id = :subscriptionId
               and subscription.status = :status
               and subscription.renewalClaimedAt is not null
               and subscription.renewalOrderId is null
            """)
    int recordRenewalOrder(
            @Param("subscriptionId") UUID subscriptionId,
            @Param("status") SubscriptionStatus status,
            @Param("renewalOrderId") UUID renewalOrderId
    );

    @Modifying
    @Query("""
            update UserSubscriptions subscription
               set subscription.renewalClaimedAt = null,
                   subscription.renewalOrderId = null
             where subscription.id = :subscriptionId
            """)
    int releaseRenewalClaim(@Param("subscriptionId") UUID subscriptionId);
}
