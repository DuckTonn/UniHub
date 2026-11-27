package com.unihub.backend.repository;

import com.unihub.backend.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {
    String REVIEW_FILTER = """
            where (:workshopId is null or w.id = :workshopId)
              and (:status = 'ALL' or p.status = :status
                   or (:status = 'NEEDS_REVIEW' and p.status in ('PROCESSING', 'FAILED', 'REFUND_REQUIRED'))
                   or (:status in ('NEEDS_REVIEW', 'REFUND_REQUIRED') and p.status = 'COMPLETED' and w.status = 'CANCELLED'))
            """;

    @Query(value = "select p from Payment p join fetch p.registration r join fetch r.workshop w " + REVIEW_FILTER + " order by p.id desc",
            countQuery = "select count(p) from Payment p join p.registration r join r.workshop w " + REVIEW_FILTER)
    Page<Payment> findForReview(Long workshopId, String status, Pageable pageable);
    Optional<Payment> findByIdempotencyKey(String idempotencyKey);
    Optional<Payment> findByRegistrationId(Long registrationId);

    @Query("select p.id from Payment p where p.status = 'PROCESSING' order by p.id")
    List<Long> findProcessingIds();

    @Query("select p.registration.workshop.id from Payment p where p.id = :id")
    Optional<Long> findWorkshopId(@Param("id") Long id);

    /**
     * Count COMPLETED payments linked to registrations of a specific workshop.
     * Used during workshop cancellation to identify paid registrants requiring refund.
     */
    @Query("""
            select count(p) from Payment p
            where p.registration.workshop.id = :workshopId
              and p.status = 'COMPLETED'
            """)
    long countCompletedByWorkshopId(@Param("workshopId") Long workshopId);

    /** Total revenue from all COMPLETED payments. */
    @Query("select coalesce(sum(p.amount), 0) from Payment p where p.status = 'COMPLETED'")
    Long sumRevenue();

    /** Count all COMPLETED payments globally. */
    @Query("select count(p) from Payment p where p.status = 'COMPLETED'")
    long countCompleted();

    /** Count all FAILED payments globally. */
    @Query("select count(p) from Payment p where p.status = 'FAILED'")
    long countFailed();

    /** Count payments for paid workshops (price > 0) that are COMPLETED. */
    @Query("""
            select count(p) from Payment p
            where p.registration.workshop.price > 0
              and p.status = 'COMPLETED'
            """)
    long countCompletedForPaidWorkshops();

    /** Count payments for paid workshops (price > 0) that are FAILED. */
    @Query("""
            select count(p) from Payment p
            where p.registration.workshop.price > 0
              and p.status = 'FAILED'
            """)
    long countFailedForPaidWorkshops();

    /**
     * Total payment attempts for paid workshops (COMPLETED + FAILED).
     * Used to compute success/failure rate.
     */
    @Query("""
            select count(p) from Payment p
            where p.registration.workshop.price > 0
              and p.status in ('COMPLETED', 'FAILED')
            """)
    long countTotalAttemptsForPaidWorkshops();

    /** Find all payments linked to a list of registration IDs (used for batch refund processing). */
    List<Payment> findByRegistrationIdIn(List<Long> registrationIds);
}
