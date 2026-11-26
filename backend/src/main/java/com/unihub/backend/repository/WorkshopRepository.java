package com.unihub.backend.repository;

import com.unihub.backend.entity.Workshop;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface WorkshopRepository extends JpaRepository<Workshop, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Workshop w where w.id = :id")
    Optional<Workshop> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            select count(w) > 0 from Workshop w
            where w.room.id = :roomId and w.id <> :id and w.status = 'PUBLISHED'
              and w.startTime < :endsAt and w.endTime > :startsAt
            """)
    boolean existsPublishedOverlap(@Param("roomId") Long roomId, @Param("id") Long id,
                                  @Param("startsAt") LocalDateTime startsAt, @Param("endsAt") LocalDateTime endsAt);

    @Modifying
    @Query("""
            update Workshop w set w.status = 'COMPLETED'
            where w.status = 'PUBLISHED' and w.endTime < :now
            """)
    int completeEndedWorkshops(@Param("now") LocalDateTime now);

    /**
     * Fetch all workshops with their Room eagerly loaded in a single query.
     * Prevents N+1 queries when mapping to WorkshopResponse (which needs room name/capacity).
     */
    @Query("""
        SELECT w FROM Workshop w 
        JOIN FETCH w.room 
        ORDER BY 
            CASE w.status 
                WHEN 'PUBLISHED' THEN 1 
                WHEN 'DRAFT' THEN 2 
                WHEN 'COMPLETED' THEN 3 
                WHEN 'CANCELLED' THEN 4 
                ELSE 5 
            END ASC, 
            w.startTime ASC
        """)
    List<Workshop> findAllWithRoom();

    /**
     * Paginated version — JOIN FETCH requires a separate countQuery to avoid
     * HibernateQueryException with pagination + fetch joins.
     */
    @Query(
            value      = """
            SELECT w FROM Workshop w 
            JOIN FETCH w.room 
            ORDER BY 
                CASE w.status 
                    WHEN 'PUBLISHED' THEN 1 
                    WHEN 'DRAFT' THEN 2 
                    WHEN 'COMPLETED' THEN 3 
                    WHEN 'CANCELLED' THEN 4 
                    ELSE 5 
                END ASC, 
                w.startTime ASC
            """,
            countQuery = "SELECT COUNT(w) FROM Workshop w"
    )
    Page<Workshop> findAllWithRoom(Pageable pageable);

    @Query("SELECT w FROM Workshop w JOIN FETCH w.room WHERE w.status = 'PUBLISHED' ORDER BY w.id ASC")
    List<Workshop> findAllPublishedWithRoom();

    /**
     * Paginated version of findAllPublishedWithRoom — separate countQuery
     * to avoid HibernateQueryException with pagination + fetch joins.
     */
    @Query(
        value      = "SELECT w FROM Workshop w JOIN FETCH w.room WHERE w.status = 'PUBLISHED' ORDER BY w.id ASC",
        countQuery = "SELECT COUNT(w) FROM Workshop w WHERE w.status = 'PUBLISHED'"
    )
    Page<Workshop> findAllPublishedWithRoom(Pageable pageable);

    /**
     * Fetch a single workshop with its Room eagerly loaded.
     * Prevents an extra query when accessing room fields in toResponse().
     */
    @Query("SELECT w FROM Workshop w JOIN FETCH w.room WHERE w.id = :id")
    Optional<Workshop> findByIdWithRoom(@Param("id") Long id);

        @Query("""
            select w from Workshop w
            join fetch w.room
            where w.status = :status
              and w.startTime between :start and :end
            """)
        List<Workshop> findByStatusAndStartTimeBetween(
            @Param("status") String status,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);
}
