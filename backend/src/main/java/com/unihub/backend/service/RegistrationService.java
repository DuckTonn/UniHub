package com.unihub.backend.service;

import com.unihub.backend.dto.PageResponse;
import com.unihub.backend.dto.PaymentResultResponse;
import com.unihub.backend.dto.RegistrationResponse;
import com.unihub.backend.dto.UserRegistrationResponse;
import com.unihub.backend.entity.Payment;
import com.unihub.backend.entity.Registration;
import com.unihub.backend.entity.User;
import com.unihub.backend.entity.Workshop;
import com.unihub.backend.event.WorkshopRegistrationSuccessEvent;
import com.unihub.backend.exception.*;
import com.unihub.backend.repository.PaymentRepository;
import com.unihub.backend.repository.RegistrationRepository;
import com.unihub.backend.repository.UserRepository;
import com.unihub.backend.repository.WorkshopRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j

public class RegistrationService {

    private final WorkshopRepository workshopRepository;
    private final RegistrationRepository registrationRepository;
    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final PaymentGatewayClient paymentGatewayClient;
    private final ApplicationEventPublisher eventPublisher;

    public static final Duration HOLD_TTL = Duration.ofMinutes(10);
    private static final List<String> OCCUPIED = List.of("PENDING", "SUCCESS");

    @Transactional
    public RegistrationResponse register(Long workshopId, Long userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new UnauthorizedException("User not found"));

        Workshop w = lockWorkshop(workshopId);

        log.info("🔍 Workshop {} status: {} | now: {}", workshopId, w.getStatus(), LocalDateTime.now());

        if ("CANCELLED".equals(w.getStatus())) {
            log.warn("❌ Workshop {} has been cancelled.", workshopId);
            throw new ConflictException("Workshop has been cancelled and is no longer accepting registrations");
        }

        if (!"PUBLISHED".equals(w.getStatus())) {
            log.warn("❌ Workshop {} not published. Status: {}", workshopId, w.getStatus());
            throw new ConflictException("Workshop is not published");
        }

        // Validate registration time window
        LocalDateTime now = LocalDateTime.now();
        log.info("⏰ Registration time: {} - {} (now: {})", w.getRegistrationStartTime(), w.getRegistrationEndTime(),
                now);
        if (now.isBefore(w.getRegistrationStartTime()) || now.isAfter(w.getRegistrationEndTime())) {
            log.warn("❌ Outside registration window for workshop {}", workshopId);
            throw new ConflictException(
                    "Registration period has ended or not yet started");
        }

        // Prevent duplicate registration for active or pending registrations
        boolean alreadyRegistered = registrationRepository.existsByUserIdAndWorkshopIdAndStatusIn(
                userId, workshopId, OCCUPIED);
        log.info("📋 User {} already has an active registration: {}", userId, alreadyRegistered);
        if (alreadyRegistered) {
            log.warn("❌ User {} already has an active registration for workshop {}", userId, workshopId);
            throw new ConflictException("User already has an active registration for this workshop");
        }

        int available = availableSeats(w);
        if (available == 0) {
            throw new SeatUnavailableException("No slots available");
        }
        w.setRemainingSlots(available - 1);
        workshopRepository.save(w);

        boolean free = w.getPrice() == 0L;
        Registration saved = registrationRepository.save(Registration.builder()
                .user(user)
                .workshop(w)
                .status(free ? "SUCCESS" : "PENDING")
                .qrCode(free ? generateQrCode() : "PENDING-" + UUID.randomUUID())
                .build());

        if (free) {
            eventPublisher.publishEvent(new WorkshopRegistrationSuccessEvent(saved.getId()));
            return RegistrationResponse.success(saved.getQrCode());
        }

        String suggestedIdempotencyKey = UUID.randomUUID().toString();
        paymentRepository.save(Payment.builder()
                .registration(saved)
                .amount(w.getPrice())
                .idempotencyKey(suggestedIdempotencyKey)
                .status("PENDING")
                .build());
        return RegistrationResponse.pending(suggestedIdempotencyKey, w.getPrice());
    }

    @Transactional(noRollbackFor = PaymentFailedException.class)
    public PaymentResultResponse processPayment(Long workshopId, Long userId, String idempotencyKey) {
        Workshop workshop = lockWorkshop(workshopId);
        Payment payment = paymentRepository.findByIdempotencyKey(idempotencyKey)
                .filter(p -> workshopId.equals(p.getRegistration().getWorkshop().getId())
                        && userId.equals(p.getRegistration().getUser().getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Payment record not found for idempotency key"));
        Registration registration = payment.getRegistration();

        if ("COMPLETED".equals(payment.getStatus())) {
            return PaymentResultResponse.success(payment.getTransactionId(), registration.getQrCode());
        }
        if ("CANCELLED".equals(workshop.getStatus())) {
            throw new ConflictException("Workshop has been cancelled. Payment cannot be processed.");
        }
        if (!"PENDING".equals(payment.getStatus()) || !"PENDING".equals(registration.getStatus())
                || !registration.getCreatedAt().plus(HOLD_TTL).isAfter(LocalDateTime.now())) {
            throw new ConflictException("Registration no longer has an active seat hold");
        }

        var result = paymentGatewayClient.charge(payment.getAmount());
        if (result.isSuccess()) {
            payment.setStatus("COMPLETED");
            payment.setTransactionId(result.getTransactionId());
            paymentRepository.save(payment);
            registration.setStatus("SUCCESS");
            registration.setQrCode(generateQrCode());
            registrationRepository.save(registration);
            eventPublisher.publishEvent(new WorkshopRegistrationSuccessEvent(registration.getId()));
            return PaymentResultResponse.success(payment.getTransactionId(), registration.getQrCode());
        }

        String reason = result.getFailureReason() != null ? result.getFailureReason() : "payment_failed";
        if (reason.contains("payment_gateway_unavailable") || reason.startsWith("gateway_http_5")) {
            throw new PaymentGatewayUnavailableException("Payment gateway unavailable");
        }

        payment.setStatus("FAILED");
        paymentRepository.save(payment);
        registration.setStatus("FAILED");
        registrationRepository.save(registration);
        syncAvailability(workshop);
        throw new PaymentFailedException("Payment failed: " + reason);
    }

    @Transactional(readOnly = true)
    public List<UserRegistrationResponse> getUserRegistrations(Long userId) {
        List<Registration> registrations = registrationRepository.findAllByUserIdWithWorkshop(userId);

        if (registrations.isEmpty()) {
            return List.of();
        }

        List<Long> registrationIds = registrations.stream().map(Registration::getId).toList();
        Map<Long, String> paymentsByRegistrationId = paymentRepository.findByRegistrationIdIn(registrationIds).stream()
                .collect(Collectors.toMap(p -> p.getRegistration().getId(), Payment::getIdempotencyKey));

        return registrations.stream()
                .map(registration -> UserRegistrationResponse.builder()
                        .registrationId(registration.getId())
                        .workshopId(registration.getWorkshop().getId())
                        .title(registration.getWorkshop().getTitle())
                        .status(registration.getStatus())
                .qrCode("SUCCESS".equals(registration.getStatus()) ? registration.getQrCode() : null)
                .paymentIdempotencyKey(paymentsByRegistrationId.get(registration.getId()))
                .startTime(registration.getWorkshop().getStartTime())
                .endTime(registration.getWorkshop().getEndTime())
                        .createdAt(registration.getCreatedAt())
                        .price(registration.getWorkshop().getPrice())
                        .build())
                .toList();
    }

    /**
     * Paginated version of getUserRegistrations (default page size: 12).
     *
     * @param userId the authenticated user's ID
     * @param page   0-indexed page number
     * @param size   number of items per page (default 12)
     */
    @Transactional(readOnly = true)
    public PageResponse<UserRegistrationResponse> getUserRegistrations(Long userId, int page, int size) {
        org.springframework.data.domain.Page<Registration> regPage =
                registrationRepository.findAllByUserIdWithWorkshop(userId,
                        org.springframework.data.domain.PageRequest.of(page, size));

        List<Long> registrationIds = regPage.getContent().stream().map(Registration::getId).toList();
        Map<Long, String> paymentsByRegistrationId = registrationIds.isEmpty()
                ? Map.of()
                : paymentRepository.findByRegistrationIdIn(registrationIds).stream()
                .collect(Collectors.toMap(p -> p.getRegistration().getId(), Payment::getIdempotencyKey));

        List<UserRegistrationResponse> content = regPage.getContent().stream()
                .map(registration -> UserRegistrationResponse.builder()
                        .registrationId(registration.getId())
                        .workshopId(registration.getWorkshop().getId())
                        .title(registration.getWorkshop().getTitle())
                        .status(registration.getStatus())
                        .qrCode("SUCCESS".equals(registration.getStatus()) ? registration.getQrCode() : null)
                        .paymentIdempotencyKey(paymentsByRegistrationId.get(registration.getId()))
                        .startTime(registration.getWorkshop().getStartTime())
                        .endTime(registration.getWorkshop().getEndTime())
                        .createdAt(registration.getCreatedAt())
                        .price(registration.getWorkshop().getPrice())
                        .build())
                .toList();

        return PageResponse.<UserRegistrationResponse>builder()
                .content(content)
                .page(regPage.getNumber())
                .size(regPage.getSize())
                .totalElements(regPage.getTotalElements())
                .totalPages(regPage.getTotalPages())
                .last(regPage.isLast())
                .build();
    }

    @Transactional
    public void cancelRegistration(Long workshopId, Long userId) {
        Workshop workshop = lockWorkshop(workshopId);
        Registration registration = registrationRepository.findByUserIdAndWorkshopIdAndStatus(userId, workshopId, "PENDING")
                .orElseThrow(() -> new ResourceNotFoundException("Pending registration not found"));
        cancelHold(workshop, registration);
    }

    @Transactional
    public void expirePendingRegistration(Long registrationId, LocalDateTime cutoff) {
        var workshopId = registrationRepository.findWorkshopId(registrationId);
        if (workshopId.isEmpty()) {
            return;
        }
        Workshop workshop = lockWorkshop(workshopId.get());
        registrationRepository.findById(registrationId)
                .filter(r -> "PENDING".equals(r.getStatus()) && !r.getCreatedAt().isAfter(cutoff))
                .ifPresent(r -> cancelHold(workshop, r));
    }

    private void cancelHold(Workshop workshop, Registration registration) {
        registration.setStatus("CANCELLED");
        registrationRepository.save(registration);
        paymentRepository.findByRegistrationId(registration.getId())
                .filter(p -> "PENDING".equals(p.getStatus()))
                .ifPresent(p -> {
                    p.setStatus("CANCELLED");
                    paymentRepository.save(p);
                });
        syncAvailability(workshop);
    }

    private Workshop lockWorkshop(Long workshopId) {
        return workshopRepository.findByIdForUpdate(workshopId)
                .orElseThrow(() -> new ResourceNotFoundException("Workshop not found"));
    }

    private int availableSeats(Workshop workshop) {
        long occupied = registrationRepository.countByWorkshopIdAndStatusIn(workshop.getId(), OCCUPIED);
        return (int) Math.max(0, workshop.getTotalSlots() - occupied);
    }

    private void syncAvailability(Workshop workshop) {
        workshop.setRemainingSlots(availableSeats(workshop));
        workshopRepository.save(workshop);
    }

    private String generateQrCode() {
        return "QR-" + UUID.randomUUID();
    }
}
