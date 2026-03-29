package io.github.marianciuc.streamingservice.payment.service.impl;

import io.github.marianciuc.streamingservice.payment.dto.common.AddressDto;
import io.github.marianciuc.streamingservice.payment.entity.Address;
import io.github.marianciuc.streamingservice.payment.entity.CardHolder;
import io.github.marianciuc.streamingservice.payment.repository.AddressRepository;
import io.github.marianciuc.streamingservice.payment.repository.CardHolderRepository;
import io.github.marianciuc.streamingservice.payment.service.UserService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AddressServiceImplTest {

    private final AddressRepository repository = mock(AddressRepository.class);
    private final UserService userService = mock(UserService.class);
    private final CardHolderRepository cardHolderRepository = mock(CardHolderRepository.class);
    private final AddressServiceImpl service = new AddressServiceImpl(repository, userService, cardHolderRepository);

    @Test
    void updateAddressUsesCardHolderAddressRelation() {
        UUID userId = UUID.randomUUID();
        Address address = Address.builder()
                .id(UUID.randomUUID())
                .line1("Old line")
                .line2("")
                .city("Old city")
                .state("NY")
                .postalCode("10001")
                .country("US")
                .build();
        CardHolder cardHolder = CardHolder.builder()
                .userId(userId)
                .address(address)
                .build();

        when(userService.extractUserIdFromAuth()).thenReturn(userId);
        when(cardHolderRepository.findById(userId)).thenReturn(Optional.of(cardHolder));

        service.updateAddress(new AddressDto(
                address.getId(),
                "New line",
                "Suite 10",
                "Los Angeles",
                "90001",
                "CA",
                "US"
        ));

        Assertions.assertEquals("New line", address.getLine1());
        Assertions.assertEquals("Suite 10", address.getLine2());
        Assertions.assertEquals("Los Angeles", address.getCity());
        Assertions.assertEquals("90001", address.getPostalCode());
        Assertions.assertEquals("CA", address.getState());
        verify(repository).save(address);
    }
}
