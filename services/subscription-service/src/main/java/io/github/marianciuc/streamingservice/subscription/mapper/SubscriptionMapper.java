package io.github.marianciuc.streamingservice.subscription.mapper;

import io.github.marianciuc.streamingservice.subscription.dto.SubscriptionRequest;
import io.github.marianciuc.streamingservice.subscription.dto.SubscriptionResponse;
import io.github.marianciuc.streamingservice.subscription.entity.RecordStatus;
import io.github.marianciuc.streamingservice.subscription.entity.Subscription;
import io.github.marianciuc.streamingservice.subscription.exceptions.NotFoundException;
import io.github.marianciuc.streamingservice.subscription.repository.SubscriptionRepository;
import io.github.marianciuc.streamingservice.subscription.service.ResolutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Class that maps Subscription related data transfers objects (DTOs) to entity classes and vice versa.
 */
@Component
@RequiredArgsConstructor
public class SubscriptionMapper {

    private final ResolutionMapper resolutionMapper;
    private final ResolutionService resolutionService;
    private final SubscriptionRepository subscriptionRepository;

    /**
     * Converts a SubscriptionRequest DTO to a Subscription entity.
     *
     * @param dto SubscriptionRequest DTO to convert.
     * @return Subscription entity.
     */
    public Subscription toEntity(SubscriptionRequest dto) {
        return Subscription.builder()
                .name(dto.name())
                .price(dto.price())
                .resolutions(dto.allowedResolutionsIds().stream().map(resolutionService::getResolution).collect(Collectors.toSet()))
                .durationInDays(dto.durationInDays())
                .description(dto.description())
                .allowedActiveSessions(dto.allowedActiveSessions())
                .currency(dto.currency())
                .recordStatus(RecordStatus.ACTIVE)
                .isTemporary(Boolean.TRUE.equals(dto.isTemporary()) || dto.nextSubscriptionId() != null)
                .nextSubscription(dto.nextSubscriptionId() == null
                        ? null
                        : subscriptionRepository.findById(dto.nextSubscriptionId())
                            .orElseThrow(() -> new NotFoundException("Subscription not found")))
                .build();
    }

    /**
     * Converts a Subscription entity to a SubscriptionResponse DTO.
     *
     * @param subscription Subscription entity to convert.
     * @return SubscriptionResponse DTO.
     */
    public SubscriptionResponse toResponse(Subscription subscription) {
        Set<?> resolutions = subscription.getResolutions() == null ? Set.of() : subscription.getResolutions();
        return new SubscriptionResponse(
                subscription.getId(),
                subscription.getName(),
                subscription.getDescription(),
                subscription.getAllowedActiveSessions(),
                subscription.getDurationInDays(),
                resolutions.stream().map(resolution -> resolutionMapper.toResolutionDto((io.github.marianciuc.streamingservice.subscription.entity.Resolution) resolution)).collect(Collectors.toSet()),
                subscription.getPrice(),
                subscription.getIsTemporary(),
                subscription.getNextSubscription() == null ? null : subscription.getNextSubscription().getId(),
                subscription.getUpdatedAt(),
                subscription.getRecordStatus(),
                subscription.getCreatedAt()
        );
    }
}
