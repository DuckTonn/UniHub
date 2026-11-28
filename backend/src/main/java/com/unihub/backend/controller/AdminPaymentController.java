package com.unihub.backend.controller;

import com.unihub.backend.dto.PageResponse;
import com.unihub.backend.dto.PaymentReviewResponse;
import com.unihub.backend.exception.PaymentGatewayUnavailableException;
import com.unihub.backend.repository.PaymentRepository;
import com.unihub.backend.service.SandboxRefundService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/v1/admin/payments")
@RequiredArgsConstructor
public class AdminPaymentController {
    public enum Status { NEEDS_REVIEW, ALL, PROCESSING, FAILED, REFUND_REQUIRED, COMPLETED, CANCELLED }

    private final PaymentRepository payments;
    private final SandboxRefundService refunds;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public PageResponse<PaymentReviewResponse> getPayments(
            @RequestParam(defaultValue = "NEEDS_REVIEW") Status status,
            @RequestParam(required = false) @Positive Long workshopId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "12") @Min(1) @Max(100) int size) {
        var result = payments.findForReview(workshopId, status.name(), PageRequest.of(page, size))
                .map(payment -> PaymentReviewResponse.from(payment,
                        payment.getRefundEligible() && refunds.availableFor(payment.getWorkshopId())));
        return PageResponse.<PaymentReviewResponse>builder()
                .content(result.getContent()).page(result.getNumber()).size(result.getSize())
                .totalElements(result.getTotalElements()).totalPages(result.getTotalPages()).last(result.isLast())
                .build();
    }

    @PostMapping("/{paymentId}/refunds")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SandboxRefundService.RefundResponse refund(@PathVariable @Positive long paymentId,
            @AuthenticationPrincipal Long adminId, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        try {
            return refunds.request(paymentId, adminId, key);
        } catch (DataAccessException exception) {
            throw new PaymentGatewayUnavailableException("Refund outcome unavailable; retry with the same key");
        }
    }
}
