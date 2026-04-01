package io.github.marianciuc.streamingservice.user.services.impl;

import io.github.marianciuc.streamingservice.user.dto.requests.RegistrationRequest;
import io.github.marianciuc.streamingservice.user.entity.User;
import io.github.marianciuc.streamingservice.user.enums.RecordStatus;
import io.github.marianciuc.streamingservice.user.enums.Role;
import io.github.marianciuc.streamingservice.user.outbox.UserOutboxService;
import io.github.marianciuc.streamingservice.user.repositories.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserRepository repository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private UserOutboxService userOutboxService;

    @InjectMocks
    private UserServiceImpl userService;

    @Test
    void createUserPersistsUserAndEnqueuesOutboxEvent() {
        RegistrationRequest request = new RegistrationRequest(
                "demo",
                "demo@example.com",
                "Password1!",
                Role.ROLE_UNSUBSCRIBED_USER
        );
        UUID userId = UUID.randomUUID();

        when(repository.existsByEmailOrUsername(request.email(), request.username())).thenReturn(false);
        when(passwordEncoder.encode(request.password())).thenReturn("encoded-password");
        when(repository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(userId);
            return user;
        });

        userService.createUser(request);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(repository).save(userCaptor.capture());
        assertEquals("demo", userCaptor.getValue().getUsername());
        assertEquals("demo@example.com", userCaptor.getValue().getEmail());
        assertEquals("encoded-password", userCaptor.getValue().getPasswordHash());
        assertEquals(RecordStatus.ACTIVE, userCaptor.getValue().getRecordStatus());
        assertTrue(Boolean.FALSE.equals(userCaptor.getValue().getIsBanned()));

        verify(userOutboxService).enqueueUserCreated(any());
    }
}
