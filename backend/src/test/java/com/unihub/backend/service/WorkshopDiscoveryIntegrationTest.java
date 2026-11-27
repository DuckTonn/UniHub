package com.unihub.backend.service;

import com.unihub.backend.dto.RoomRequest;
import com.unihub.backend.dto.WorkshopFilters;
import com.unihub.backend.dto.WorkshopRequest;
import com.unihub.backend.dto.WorkshopResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class WorkshopDiscoveryIntegrationTest {
    @Autowired private RoomService rooms;
    @Autowired private WorkshopService workshops;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void matchesTitleOrSpeakerAndFiltersBeforeCountingAndPaginating() {
        var day = LocalDate.now().plusDays(5);
        String term = "Discovery-" + UUID.randomUUID() + "%";
        Long room = rooms.createRoom(new RoomRequest("Search room " + UUID.randomUUID(), 12)).getId();
        Long first = create(room, term, null, day.atTime(8, 0), 0L, true);
        Long second = create(room, "Workshop by speaker", term, day.atTime(10, 0), 50000L, true);
        Long full = create(room, term + " full", null, day.atTime(12, 0), 0L, true);
        jdbc.update("UPDATE workshops SET remaining_slots = 0 WHERE id = ?", full);
        create(room, term + " draft", null, day.atTime(14, 0), 0L, false);
        create(room, term + " tomorrow", null, day.plusDays(1).atStartOfDay(), 0L, true);

        var filters = new WorkshopFilters(term.toUpperCase(Locale.ROOT), day, day, null, true);
        var page0 = workshops.getPublishedWorkshops(null, 0, 1, filters);
        var page1 = workshops.getPublishedWorkshops(null, 1, 1, filters);
        assertThat(page0.getTotalElements()).isEqualTo(2);
        assertThat(page0.getTotalPages()).isEqualTo(2);
        assertThat(page0.getContent()).extracting(WorkshopResponse::getId).containsExactly(first);
        assertThat(page1.getContent()).extracting(WorkshopResponse::getId).containsExactly(second);
        var free = new WorkshopFilters(term, day, day, WorkshopFilters.Fee.FREE, true);
        assertThat(workshops.getPublishedWorkshops(null, 0, 5, free).getContent())
                .extracting(WorkshopResponse::getId).containsExactly(first);
        var speakerOnly = new WorkshopFilters(term, null, null, WorkshopFilters.Fee.PAID, false);
        assertThat(workshops.getPublishedWorkshops(null, 0, 5, speakerOnly).getContent())
                .extracting(WorkshopResponse::getId).containsExactly(second);
    }

    @Test
    void rejectsReversedDates() {
        var today = LocalDate.now();
        assertThatThrownBy(() -> new WorkshopFilters("", today.plusDays(1), today, null, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Long create(Long room, String title, String speaker, LocalDateTime start, long price, boolean published) {
        Long id = workshops.createWorkshop(new WorkshopRequest(title, null, room, speaker, 5, price,
                start, start.plusHours(1), start.minusDays(3), start.minusDays(1))).getId();
        if (published) workshops.publishWorkshop(id);
        return id;
    }
}
