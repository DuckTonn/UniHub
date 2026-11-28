package com.unihub.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import com.fasterxml.jackson.annotation.JsonInclude;

@Data
@Builder
@AllArgsConstructor
public class PaymentResultResponse {
    private boolean success;
    private String transactionId;
    private String message;
    private String qrCode;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String checkoutStatus;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String checkoutUrl;

    public static PaymentResultResponse checkout(String status, String url) {
        return PaymentResultResponse.builder().checkoutStatus(status).checkoutUrl(url).build();
    }

    public static PaymentResultResponse success(String txId) {
        return PaymentResultResponse.builder().success(true).transactionId(txId).build();
    }

    public static PaymentResultResponse success(String txId, String qrCode) {
        return PaymentResultResponse.builder().success(true).transactionId(txId).qrCode(qrCode).build();
    }

    public static PaymentResultResponse failed(String msg) {
        return PaymentResultResponse.builder().success(false).message(msg).build();
    }
}
