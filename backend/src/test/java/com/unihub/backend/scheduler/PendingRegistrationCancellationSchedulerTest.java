package com.unihub.backend.scheduler;

import com.unihub.backend.repository.RegistrationRepository;
import com.unihub.backend.service.RegistrationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PendingRegistrationCancellationSchedulerTest {

    @Mock private RegistrationRepository registrations;
    @Mock private RegistrationService registrationService;
    @InjectMocks private PendingRegistrationCancellationScheduler scheduler;

    @Test
    void continuesExpiringHoldsAfterOneTransactionFails() {
        when(registrations.findExpiredPendingIds(any(LocalDateTime.class))).thenReturn(List.of(11L, 12L));
        doThrow(new IllegalStateException()).when(registrationService)
                .expirePendingRegistration(eq(11L), any(LocalDateTime.class));

        scheduler.cancelStalePendingRegistrations();

        verify(registrationService).expirePendingRegistration(eq(12L), any(LocalDateTime.class));
    }
}
