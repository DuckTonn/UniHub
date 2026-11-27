package com.unihub.backend.dto;

import com.unihub.backend.entity.Payment;

public record PaymentReviewResponse(Long paymentId, Long registrationId, Long workshopId, String workshopTitle,
                                    Long amount, String status, String transactionReference, String issue) {
    public static PaymentReviewResponse from(Payment payment) {
        var registration = payment.getRegistration();
        var workshop = registration.getWorkshop();
        String issue = switch (payment.getStatus()) {
            case "PROCESSING" -> "Awaiting a definitive payment provider outcome";
            case "REFUND_REQUIRED" -> "Payment succeeded after the booking became ineligible; confirm refund with the provider";
            case "COMPLETED" -> "CANCELLED".equals(workshop.getStatus())
                    ? "Workshop cancelled after payment; verify the refund with the provider" : null;
            case "FAILED" -> "invalid_amount".equals(payment.getFailureReason())
                    ? "Provider rejected the amount" : "Payment provider reported a failure";
            case "CANCELLED" -> payment.getFailureReason() == null ? null : "Seat hold closed before payment settled";
            default -> null;
        };
        return new PaymentReviewResponse(payment.getId(), registration.getId(), workshop.getId(), workshop.getTitle(),
                payment.getAmount(), payment.getStatus(), payment.getTransactionId(), issue);
    }
}
