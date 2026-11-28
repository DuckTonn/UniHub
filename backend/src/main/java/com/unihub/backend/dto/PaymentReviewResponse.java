package com.unihub.backend.dto;

import com.unihub.backend.repository.PaymentRepository.ReviewEntry;

public record PaymentReviewResponse(Long paymentId, Long registrationId, Long workshopId, String workshopTitle,
                                    Long amount, String status, String transactionReference, String issue,
                                    String refundStatus, String refundReference, String refundFailureCode,
                                    boolean sandboxRefundAvailable) {
    public static PaymentReviewResponse from(ReviewEntry payment, boolean sandboxRefundAvailable) {
        String issue = switch (payment.getStatus()) {
            case "PROCESSING" -> "Awaiting a definitive payment provider outcome";
            case "REFUND_REQUIRED" -> "Payment succeeded after the booking became ineligible; confirm refund with the provider";
            case "COMPLETED" -> "CANCELLED".equals(payment.getWorkshopStatus())
                    ? "Workshop cancelled after payment; verify the refund with the provider" : null;
            case "FAILED" -> "invalid_amount".equals(payment.getFailureReason())
                    ? "Provider rejected the amount" : "Payment provider reported a failure";
            case "CANCELLED" -> payment.getFailureReason() == null ? null : "Seat hold closed before payment settled";
            default -> null;
        };
        return new PaymentReviewResponse(payment.getPaymentId(), payment.getRegistrationId(), payment.getWorkshopId(),
                payment.getWorkshopTitle(), payment.getAmount(), payment.getStatus(), payment.getTransactionReference(),
                payment.getRefundStatus() == null ? issue : null, payment.getRefundStatus(),
                payment.getRefundReference(), payment.getRefundFailureCode(), sandboxRefundAvailable);
    }
}
