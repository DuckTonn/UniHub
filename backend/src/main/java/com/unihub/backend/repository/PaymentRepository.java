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
            where (CAST(:workshopId AS bigint) is null or w.id = :workshopId)
              and (:status = 'ALL' or p.status = :status
                   or (:status = 'NEEDS_REVIEW' and p.status in ('PROCESSING', 'FAILED', 'REFUND_REQUIRED'))
                   or (:status in ('NEEDS_REVIEW', 'REFUND_REQUIRED') and p.status = 'COMPLETED' and w.status = 'CANCELLED'))
              and (:status not in ('NEEDS_REVIEW', 'REFUND_REQUIRED') or f.status is distinct from 'SUCCEEDED')
            """;
    String REVIEW_JOINS = """
            from payments p join registrations r on r.id = p.registration_id
            join workshops w on w.id = r.workshop_id
            left join payment_refunds f on f.payment_id = p.id
            """;

    @Query(value = """
            select p.id as "paymentId", r.id as "registrationId", w.id as "workshopId",
                   w.title as "workshopTitle", p.amount, p.status, w.status as "workshopStatus",
                   p.transaction_id as "transactionReference", p.failure_reason as "failureReason",
                   f.status as "refundStatus", f.refund_reference as "refundReference", f.failure_code as "refundFailureCode",
                   (f.id is null and p.amount > 0
                    and (p.status = 'REFUND_REQUIRED' or (p.status = 'COMPLETED' and w.status = 'CANCELLED'))
                    and m.transaction_id is not null) as "refundEligible"
            """ + REVIEW_JOINS + """
            left join mock_payment_operations m on m.idempotency_key = p.idempotency_key
              and m.transaction_id = p.transaction_id and m.amount = p.amount
            """ + REVIEW_FILTER + " order by p.id desc",
            countQuery = "select count(*) " + REVIEW_JOINS + REVIEW_FILTER, nativeQuery = true)
    Page<ReviewEntry> findForReview(Long workshopId, String status, Pageable pageable);

    interface ReviewEntry {
        long getPaymentId();
        long getRegistrationId();
        long getWorkshopId();
        String getWorkshopTitle();
        long getAmount();
        String getStatus();
        String getWorkshopStatus();
        String getTransactionReference();
        String getFailureReason();
        String getRefundStatus();
        String getRefundReference();
        String getRefundFailureCode();
        boolean getRefundEligible();
    }
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
