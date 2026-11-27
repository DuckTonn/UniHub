package com.unihub.backend.service.notification;

import com.unihub.backend.dto.NotificationResponse;
import com.unihub.backend.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class NotificationReadIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private NotificationService notifications;

    @Test
    void broadcastReceiptIsPerUserAndRepeatedWritesAreIdempotent() {
        var users = jdbc.queryForList("SELECT id FROM users ORDER BY id LIMIT 2", Long.class);
        Long broadcast = insertNotification(null, "IN_APP", "SUCCESS");

        assertThat(isRead(users.getFirst(), broadcast)).isFalse();
        notifications.markRead(users.getFirst(), broadcast);
        notifications.markRead(users.getFirst(), broadcast);

        assertThat(isRead(users.getFirst(), broadcast)).isTrue();
        assertThat(isRead(users.getLast(), broadcast)).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_read_receipts WHERE notification_id = ?",
                Long.class, broadcast)).isEqualTo(1L);
    }

    @Test
    void unreadPagesAndCountUseTheSamePersonalAndBroadcastVisibility() {
        var users = jdbc.queryForList("SELECT id FROM users ORDER BY id LIMIT 2", Long.class);
        Long reader = users.getFirst();
        long baseline = notifications.countUnread(reader);
        Long broadcast = insertNotification(null, "IN_APP", "SUCCESS");
        Long personal = insertNotification(reader, "IN_APP", "SUCCESS");
        insertNotification(users.getLast(), "IN_APP", "SUCCESS");
        insertNotification(reader, "EMAIL", "SUCCESS");

        assertThat(notifications.countUnread(reader)).isEqualTo(baseline + 2);
        var firstPage = notifications.getUserNotifications(reader, 0, 1, true);
        assertThat(firstPage.getTotalElements()).isEqualTo(baseline + 2);
        assertThat(firstPage.getContent()).extracting(NotificationResponse::id).containsExactly(personal);
        notifications.markRead(reader, personal);
        assertThat(notifications.countUnread(reader)).isEqualTo(baseline + 1);
        assertThat(notifications.getUserNotifications(reader, 0, 1, true).getContent())
                .extracting(NotificationResponse::id).containsExactly(broadcast);
    }

    @Test
    void notificationsOutsideTheInboxCannotBeMarkedRead() {
        var users = jdbc.queryForList("SELECT id FROM users ORDER BY id LIMIT 2", Long.class);
        Long personal = insertNotification(users.getLast(), "IN_APP", "SUCCESS");
        Long email = insertNotification(users.getFirst(), "EMAIL", "SUCCESS");
        Long unsent = insertNotification(users.getFirst(), "IN_APP", "PENDING");

        for (Long id : new Long[]{personal, email, unsent}) {
            assertThatThrownBy(() -> notifications.markRead(users.getFirst(), id))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM notification_read_receipts WHERE notification_id IN (?, ?, ?)
                """, Long.class, personal, email, unsent)).isZero();
    }

    private boolean isRead(Long userId, Long notificationId) {
        return notifications.getUserNotifications(userId, 0, 10).getContent().stream()
                .filter(notification -> notification.id().equals(notificationId))
                .findFirst()
                .map(NotificationResponse::read)
                .orElseThrow();
    }

    private Long insertNotification(Long userId, String channel, String status) {
        return jdbc.queryForObject("""
                INSERT INTO notifications (user_id, title, content_html, channel, status)
                VALUES (?, 'Receipt test', '<p>Test notification</p>', ?, ?) RETURNING id
                """, Long.class, userId, channel, status);
    }
}
