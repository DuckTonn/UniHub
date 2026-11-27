package com.unihub.backend.repository;

import com.unihub.backend.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {
    @Query("SELECT n FROM Notification n WHERE n.channel = :channel AND n.status = 'SUCCESS' AND (n.user.id = :userId OR n.user IS NULL)")
    Page<Notification> findInAppNotificationsForUser(
            @Param("userId") Long userId,
            @Param("channel") String channel,
            Pageable pageable
    );

    List<Notification> findByStatusAndRetryCountLessThan(String status, int maxRetries);

    String UNREAD_INBOX = """
            FROM notifications n
            WHERE n.channel = 'IN_APP' AND n.status = 'SUCCESS'
              AND (n.user_id = :userId OR n.user_id IS NULL)
              AND NOT EXISTS (
                    SELECT 1 FROM notification_read_receipts r
                    WHERE r.user_id = :userId AND r.notification_id = n.id
              )
            """;

    @Query(value = "SELECT n.* " + UNREAD_INBOX + " ORDER BY n.created_at DESC, n.id DESC",
            countQuery = "SELECT COUNT(*) " + UNREAD_INBOX, nativeQuery = true)
    Page<Notification> findUnread(@Param("userId") Long userId, Pageable pageable);

    @Query(value = "SELECT COUNT(*) " + UNREAD_INBOX, nativeQuery = true)
    long countUnread(@Param("userId") Long userId);

    @Query(value = """
            SELECT notification_id FROM notification_read_receipts
            WHERE user_id = :userId AND notification_id IN (:notificationIds)
            """, nativeQuery = true)
    List<Long> findReadIds(@Param("userId") Long userId, @Param("notificationIds") List<Long> notificationIds);

    @Modifying
    @Query(value = """
            INSERT INTO notification_read_receipts (user_id, notification_id)
            SELECT :userId, n.id FROM notifications n
            WHERE n.id = :notificationId AND n.channel = 'IN_APP' AND n.status = 'SUCCESS'
              AND (n.user_id = :userId OR n.user_id IS NULL)
            ON CONFLICT (user_id, notification_id) DO NOTHING
            """, nativeQuery = true)
    int insertReadReceipt(@Param("userId") Long userId, @Param("notificationId") Long notificationId);

    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM notifications n
                WHERE n.id = :notificationId AND n.channel = 'IN_APP' AND n.status = 'SUCCESS'
                  AND (n.user_id = :userId OR n.user_id IS NULL)
            )
            """, nativeQuery = true)
    boolean isVisibleInInbox(@Param("userId") Long userId, @Param("notificationId") Long notificationId);
}
