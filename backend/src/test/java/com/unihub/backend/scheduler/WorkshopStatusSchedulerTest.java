package com.unihub.backend.scheduler;

import com.unihub.backend.repository.WorkshopRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class WorkshopStatusSchedulerTest {

    @Mock private WorkshopRepository workshops;
    @InjectMocks private WorkshopStatusScheduler scheduler;

    @Test
    void delegatesCompletionToTheConditionalDatabaseUpdate() {
        scheduler.autoUpdateWorkshopStatus();
        verify(workshops).completeEndedWorkshops(any(LocalDateTime.class));
    }
}
