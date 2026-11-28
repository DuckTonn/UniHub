package com.unihub.backend.scheduler;

import com.unihub.backend.repository.WorkshopRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
@ConditionalOnProperty(name = "app.scheduled-maintenance.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class WorkshopStatusScheduler {

    private final WorkshopRepository workshopRepository;

    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void autoUpdateWorkshopStatus() {
        int completed = workshopRepository.completeEndedWorkshops(LocalDateTime.now());
        if (completed > 0) {
            log.info("Marked {} ended workshops as COMPLETED", completed);
        }
    }
}
