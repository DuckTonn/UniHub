package com.unihub.backend.service;

import com.unihub.backend.dto.PageResponse;
import com.unihub.backend.dto.WorkshopAttendanceResponse;
import com.unihub.backend.dto.WorkshopFilters;
import com.unihub.backend.dto.WorkshopRequest;
import com.unihub.backend.dto.WorkshopResponse;
import com.unihub.backend.dto.WorkshopStatsResponse;
import com.unihub.backend.entity.Registration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import com.unihub.backend.entity.Room;
import com.unihub.backend.entity.Workshop;
import com.unihub.backend.dto.NotificationRecipient;
import com.unihub.backend.exception.ConflictException;
import com.unihub.backend.exception.ResourceNotFoundException;
import com.unihub.backend.event.WorkshopCancelledEvent;
import com.unihub.backend.repository.PaymentRepository;
import com.unihub.backend.repository.RegistrationRepository;
import com.unihub.backend.repository.RoomRepository;
import com.unihub.backend.repository.WorkshopRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkshopService {

    private final WorkshopRepository workshopRepository;
    private final RoomRepository roomRepository;
    private final RegistrationRepository registrationRepository;
    private final PaymentRepository paymentRepository;
    private final ApplicationEventPublisher eventPublisher;

    // ────────────── Admin ──────────────

    public List<WorkshopResponse> getAllWorkshops() {
        List<Workshop> workshops = workshopRepository.findAllWithRoom();

        List<Long> workshopIds = workshops.stream().map(Workshop::getId).toList();
        Map<Long, Long> successfulCounts = getSuccessfulCountsForWorkshops(workshopIds);

        return workshops.stream()
                .map(w -> toResponse(w, null, successfulCounts.getOrDefault(w.getId(), 0L)))
                .toList();
    }

    /**
     * Paginated version of getAllWorkshops (default page size: 12).
     *
     * @param page 0-indexed page number
     * @param size number of items per page (default 12)
     */
    public PageResponse<WorkshopResponse> getAllWorkshops(int page, int size) {
        Page<Workshop> workshopPage = workshopRepository.findAllWithRoom(
                PageRequest.of(page, size));

        List<WorkshopResponse> content = workshopPage.getContent()
                .stream()
                .map(this::toResponse)
                .toList();

        return PageResponse.<WorkshopResponse>builder()
                .content(content)
                .page(workshopPage.getNumber())
                .size(workshopPage.getSize())
                .totalElements(workshopPage.getTotalElements())
                .totalPages(workshopPage.getTotalPages())
                .last(workshopPage.isLast())
                .build();
    }

    public List<WorkshopResponse> getPublishedWorkshops() {
        return workshopRepository.findAllPublishedWithRoom()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public List<WorkshopResponse> getPublishedWorkshops(Long userId) {
        Map<Long, String> userRegistrationStatuses = getUserRegistrationStatuses(userId);
        List<Workshop> workshops = workshopRepository.findAllPublishedWithRoom();

        List<Long> workshopIds = workshops.stream().map(Workshop::getId).toList();
        Map<Long, Long> successfulCounts = getSuccessfulCountsForWorkshops(workshopIds);

        return workshops.stream()
                .map(w -> toResponse(w, userRegistrationStatuses.get(w.getId()), successfulCounts.getOrDefault(w.getId(), 0L)))
                .toList();
    }

    /**
     * Paginated version of getPublishedWorkshops (default page size: 12).
     *
     * @param userId nullable — if authenticated, includes user-specific
     *               registration info
     * @param page   0-indexed page number
     * @param size   number of items per page (default 12)
     */
    public PageResponse<WorkshopResponse> getPublishedWorkshops(Long userId, int page, int size) {
        return getPublishedWorkshops(userId, page, size, new WorkshopFilters(null, null, null, null, false));
    }

    public PageResponse<WorkshopResponse> getPublishedWorkshops(Long userId, int page, int size, WorkshopFilters filters) {
        Map<Long, String> userRegistrationStatuses = getUserRegistrationStatuses(userId);

        PageRequest paging = PageRequest.of(page, size);
        Page<Workshop> workshopPage = filters.isEmpty() ? workshopRepository.findAllPublishedWithRoom(paging)
                : workshopRepository.searchPublishedWithRoom(filters.q(),
                        filters.from() == null, filters.from() == null ? null : filters.from().atStartOfDay(),
                        filters.to() == null, filters.to() == null ? null : filters.to().plusDays(1).atStartOfDay(),
                        filters.fee() == null ? "" : filters.fee().name(), filters.seatsAvailable(), paging);

        List<Long> workshopIds = workshopPage.getContent().stream().map(Workshop::getId).toList();
        Map<Long, Long> successfulCounts = getSuccessfulCountsForWorkshops(workshopIds);

        List<WorkshopResponse> content = workshopPage.getContent()
                .stream()
                .map(w -> toResponse(w, userRegistrationStatuses.get(w.getId()), successfulCounts.getOrDefault(w.getId(), 0L)))
                .toList();

        return PageResponse.<WorkshopResponse>builder()
                .content(content)
                .page(workshopPage.getNumber())
                .size(workshopPage.getSize())
                .totalElements(workshopPage.getTotalElements())
                .totalPages(workshopPage.getTotalPages())
                .last(workshopPage.isLast())
                .build();
    }

    public WorkshopResponse getWorkshopById(Long id) {
        Workshop workshop = findWorkshopWithRoomOrThrow(id);
        return toResponse(workshop);
    }

    public WorkshopResponse getPublishedWorkshopById(Long id) {
        Workshop workshop = findWorkshopWithRoomOrThrow(id);
        if (!"PUBLISHED".equals(workshop.getStatus())) {
            throw new ResourceNotFoundException("Workshop not found or is not currently available");
        }
        return toResponse(workshop);
    }

    public WorkshopResponse getPublishedWorkshopById(Long id, Long userId) {
        Workshop workshop = findWorkshopWithRoomOrThrow(id);
        if (!"PUBLISHED".equals(workshop.getStatus())) {
            throw new ResourceNotFoundException("Workshop not found or is not currently available");
        }

        long count = registrationRepository.countByWorkshopIdAndStatus(id, "SUCCESS");

        String userStatus = null;
        if (userId != null) {
            Optional<Registration> regOpt = registrationRepository.findByUserIdAndWorkshopId(userId, id);
            if (regOpt.isPresent() && ("PENDING".equals(regOpt.get().getStatus()) || "SUCCESS".equals(regOpt.get().getStatus()))) {
                userStatus = regOpt.get().getStatus();
            }
        }

        return toResponse(workshop, userStatus, count);
    }

    @Transactional
    public WorkshopResponse createWorkshop(WorkshopRequest request) {
        // 1. Lookup Room
        Room room = findRoomOrThrow(request.roomId());

        // 2. Validate all business rules
        validateBusinessRules(request, room);

        String speaker = (request.speaker() == null || request.speaker().isBlank()) ? "TBD" : request.speaker().trim();

        // 3. Build entity — status defaults to DRAFT via @Builder.Default
        Workshop workshop = Workshop.builder()
                .title(request.title())
                .description(request.description())
                .room(room)
                .speaker(speaker)
                // status defaults to "DRAFT" via @Builder.Default
                .totalSlots(request.totalSlots())
                .remainingSlots(request.totalSlots())
                .price(request.price() != null ? request.price() : 0L)
                .startTime(request.startTime())
                .endTime(request.endTime())
                .registrationStartTime(request.registrationStartTime())
                .registrationEndTime(request.registrationEndTime())
                .build();

        Workshop saved = workshopRepository.save(workshop);

        return toResponse(saved);
    }

    @Transactional
    public WorkshopResponse updateWorkshop(Long id, WorkshopRequest request) {
        Workshop workshop = findWorkshopForUpdate(id);

        // 1. Lookup Room (maybe the same or different)
        Room room = findRoomOrThrow(request.roomId());

        // 2. Validate all business rules
        validateBusinessRules(request, room);

        int oldTotalSlots = workshop.getTotalSlots();
        long occupied = registrationRepository.countByWorkshopIdAndStatusIn(id, List.of("PENDING", "SUCCESS"));
        if ("PUBLISHED".equals(workshop.getStatus())) {
            requireAvailableRoom(room.getId(), id, request.startTime(), request.endTime());
        }

        // 3. Check total_slots and workshop status for update rules
        boolean isDraft = "DRAFT".equals(workshop.getStatus());
        if (!isDraft && !Integer.valueOf(oldTotalSlots).equals(request.totalSlots())) {
            throw new ConflictException(
                    "Cannot set total_slots to " + request.totalSlots() +
                            " because workshop is not in DRAFT status");
        }

        long successfulCount = registrationRepository.countByWorkshopIdAndStatus(id, "SUCCESS");
        if (request.totalSlots() < successfulCount) {
            throw new ConflictException("Cannot reduce total slots below the number of successful registrations (" + successfulCount + ")");
        }

        // Price change validation: Cannot change price if there are already active or successful registrations
        if (request.price() != null && !request.price().equals(workshop.getPrice())) {
            if (registrationRepository.existsByWorkshopIdAndStatusIn(id, List.of("SUCCESS", "PENDING"))) {
                throw new ConflictException("Cannot update price because this workshop already has active or successful registrations");
            }
        }

        String speaker = (request.speaker() == null || request.speaker().isBlank()) ? "TBD" : request.speaker().trim();

        // 4. Update all fields
        workshop.setTitle(request.title());
        workshop.setDescription(request.description());
        workshop.setRoom(room);
        workshop.setSpeaker(speaker);
        workshop.setTotalSlots(request.totalSlots());

        if (request.totalSlots() < occupied) {
            throw new ConflictException("Cannot reduce total slots below active registrations");
        }
        workshop.setRemainingSlots((int) (request.totalSlots() - occupied));

        workshop.setPrice(request.price() != null ? request.price() : 0L);
        workshop.setStartTime(request.startTime());
        workshop.setEndTime(request.endTime());
        workshop.setRegistrationStartTime(request.registrationStartTime());
        workshop.setRegistrationEndTime(request.registrationEndTime());
        // Note: status is NOT updated via this endpoint — managed by lifecycle

        Workshop saved = workshopRepository.save(workshop);

        return toResponse(saved);
    }

    @Transactional
    public void deleteWorkshop(Long id) {
        Workshop workshop = findWorkshopForUpdate(id);

        // G10: Only allow deletion of DRAFT workshops
        if (!"DRAFT".equals(workshop.getStatus())) {
            throw new ConflictException("Only DRAFT workshops can be deleted");
        }

        workshopRepository.delete(workshop);

    }

    /**
     * Publish a workshop (DRAFT → PUBLISHED).
     * Only DRAFT workshops can be published.
     */
    @Transactional
    public void publishWorkshop(Long id) {
        Workshop workshop = findWorkshopForUpdate(id);

        if (!"DRAFT".equals(workshop.getStatus())) {
            throw new IllegalArgumentException(
                    "Only DRAFT workshops can be published. Current status: " + workshop.getStatus());
        }

        Room room = findRoomOrThrow(workshop.getRoom().getId());
        if (workshop.getTotalSlots() > room.getCapacity()) {
            throw new ConflictException("Workshop total slots exceed room capacity");
        }
        requireAvailableRoom(room.getId(), id, workshop.getStartTime(), workshop.getEndTime());
        workshop.setStatus("PUBLISHED");
        workshopRepository.save(workshop);
        log.info("Workshop {} has been published.", id);
    }

    /**
     * Cancel a published workshop while preserving completed payments for reconciliation.
     */
    @Transactional
    public void cancelWorkshop(Long id) {
        Workshop workshop = findWorkshopForUpdate(id);

        String currentStatus = workshop.getStatus();
        if (!"PUBLISHED".equals(currentStatus)) {
            if ("DRAFT".equals(currentStatus)) {
                throw new IllegalArgumentException(
                        "Cannot cancel a DRAFT workshop. Use DELETE to remove it instead.");
            }
            throw new IllegalArgumentException(
                    "Cannot cancel a workshop that is already " + currentStatus);
        }

        // Step 1: Update status workshop → CANCELLED
        workshop.setStatus("CANCELLED");
        workshopRepository.save(workshop);
        log.info("Workshop {}: status set to CANCELLED.", id);

        List<NotificationRecipient> recipients = registrationRepository
            .findRecipientsByWorkshopIdAndStatus(id, "SUCCESS");

        // Step 2: Bulk-cancel registrations in SUCCESS or PENDING
        int cancelledCount = registrationRepository.bulkCancelByWorkshopId(
                id, List.of("SUCCESS", "PENDING"));
        log.info("Workshop {}: {} registration(s) bulk-cancelled (SUCCESS/PENDING → CANCELLED).",
                id, cancelledCount);

        // Step 3: Keep COMPLETED payments — just count to log/notify
        // (don't update payments table — to keep records for refund)
        long paidCount = paymentRepository.countCompletedByWorkshopId(id);
        log.info("Workshop {}: {} COMPLETED payment(s) preserved for refund reconciliation.",
                id, paidCount);

        workshop.setRemainingSlots(workshop.getTotalSlots());

        if (workshop.getPrice() != null && workshop.getPrice() > 0 && paidCount > 0) {
            log.info("Workshop {} (price={}): {} paid registrant(s) flagged for refund notice.",
                id, workshop.getPrice(), paidCount);
        }

        if (!recipients.isEmpty()) {
            eventPublisher.publishEvent(new WorkshopCancelledEvent(
                    workshop.getId(),
                    workshop.getTitle(),
                    workshop.getStartTime(),
                    workshop.getRoom() != null ? workshop.getRoom().getName() : "TBD",
                    workshop.getPrice(),
                    recipients
            ));
        }
    }

    public WorkshopStatsResponse getWorkshopStats(Long id) {
        Workshop workshop = findWorkshopOrThrow(id);

        // Read remaining_slots from Redis (authoritative source)
        int remainingSlots = workshop.getRemainingSlots();

        long registeredCount = registrationRepository.countByWorkshopIdAndStatus(id, "SUCCESS");

        double fillRate = workshop.getTotalSlots() > 0
                ? (double) (workshop.getTotalSlots() - remainingSlots) / workshop.getTotalSlots() * 100
                : 0.0;

        fillRate = BigDecimal.valueOf(fillRate)
                .setScale(1, RoundingMode.HALF_UP)
                .doubleValue();

        return WorkshopStatsResponse.builder()
                .workshopId(workshop.getId())
                .title(workshop.getTitle())
                .totalSlots(workshop.getTotalSlots())
                .remainingSlots(remainingSlots)
                .registeredCount(registeredCount)
                .fillRate(fillRate)
                .build();
    }

    /**
     * Returns the paginated attendance list for a given workshop.
     * Only includes registrations with {@code status = 'SUCCESS'}.
     * Records are sorted: checked-in first, then by registration time ascending.
     *
     * @param workshopId the workshop ID
     * @param page       0-indexed page number
     * @param size       number of records per page
     * @return paginated attendance records
     * @throws ResourceNotFoundException if the workshop does not exist
     */
    public PageResponse<WorkshopAttendanceResponse> getWorkshopAttendances(
            Long workshopId, int page, int size) {

        findWorkshopOrThrow(workshopId);

        Pageable pageable = PageRequest.of(page, size);
        Page<WorkshopAttendanceResponse> result = registrationRepository
                .findAttendancesByWorkshopId(workshopId, pageable)
                .map(this::toAttendanceResponse);

        return PageResponse.<WorkshopAttendanceResponse>builder()
                .content(result.getContent())
                .page(result.getNumber())
                .size(result.getSize())
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .last(result.isLast())
                .build();
    }

    // ────────────── Private helpers ──────────────

    private Workshop findWorkshopOrThrow(Long id) {
        return workshopRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Workshop not found with id: " + id));
    }

    /**
     * Find workshop with Room eagerly loaded (JOIN FETCH) to avoid N+1 queries.
     */
    private Workshop findWorkshopWithRoomOrThrow(Long id) {
        return workshopRepository.findByIdWithRoom(id)
                .orElseThrow(() -> new ResourceNotFoundException("Workshop not found with id: " + id));
    }

    private Room findRoomOrThrow(Long roomId) {
        return roomRepository.findByIdForUpdate(roomId)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found with id: " + roomId));
    }

    /**
     * Validate all business rules for create/update (G4/G5/G6/G24).
     */
    private void validateBusinessRules(WorkshopRequest request, Room room) {
        // 1. end_time > start_time
        validateTimeRange(request.startTime(), request.endTime());

        // 2. start_time and end_time must be on the same day (G24 — mandatory per
        // design.md)
        if (request.startTime() != null && request.endTime() != null
                && !request.startTime().toLocalDate().equals(request.endTime().toLocalDate())) {
            throw new IllegalArgumentException("start_time and end_time must be on the same day");
        }

        // 3. registration_start_time < registration_end_time
        if (request.registrationEndTime() != null && request.registrationStartTime() != null
                && !request.registrationEndTime().isAfter(request.registrationStartTime())) {
            throw new IllegalArgumentException(
                    "registration_end_time must be after registration_start_time");
        }

        // 4. registration_start_time < start_time
        if (request.registrationStartTime() != null && request.startTime() != null
                && !request.startTime().isAfter(request.registrationStartTime())) {
            throw new IllegalArgumentException(
                    "registration_start_time must be before start_time");
        }

        // 5. registration_end_time must be at least 1 day before start_time (G5b)
        if (request.registrationEndTime() != null && request.startTime() != null) {
            long hoursBetween = java.time.Duration.between(
                    request.registrationEndTime(), request.startTime()).toHours();
            if (hoursBetween < 24) {
                throw new IllegalArgumentException(
                        "registration_end_time must be at least 1 day before start_time");
            }
        }

        // 6. total_slots <= room.capacity (G6)
        if (request.totalSlots() > room.getCapacity()) {
            throw new ConflictException(
                    "total_slots (" + request.totalSlots() + ") exceeds room capacity ("
                            + room.getCapacity() + ")");
        }
    }

    private void validateTimeRange(LocalDateTime startTime, LocalDateTime endTime) {
        if (endTime != null && startTime != null && !endTime.isAfter(startTime)) {
            throw new IllegalArgumentException("end_time must be after start_time");
        }
    }

    private Workshop findWorkshopForUpdate(Long id) {
        return workshopRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Workshop not found with id: " + id));
    }

    private void requireAvailableRoom(Long roomId, Long workshopId, LocalDateTime start, LocalDateTime end) {
        if (workshopRepository.existsPublishedOverlap(roomId, workshopId, start, end)) {
            throw new ConflictException("Room is already booked for this workshop time");
        }
    }

    /**
     * Convert Workshop entity to WorkshopResponse DTO (G11/G23).
     * Includes room info to avoid N+1 — Room is already fetched via JOIN FETCH.
     */
    private WorkshopResponse toResponse(Workshop w) {
        long count = registrationRepository.countByWorkshopIdAndStatus(w.getId(), "SUCCESS");
        return toResponse(w, null, count);
    }

    private WorkshopResponse toResponse(Workshop w, String userRegistrationStatus, long successfulCount) {
        int remainingSlots = w.getRemainingSlots();

        return WorkshopResponse.builder()
                .id(w.getId())
                .title(w.getTitle())
                .description(w.getDescription())
                .roomId(w.getRoom().getId())
                .roomName(w.getRoom().getName())
                .roomCapacity(w.getRoom().getCapacity())
                .layoutMapUrl(w.getRoom().getLayoutMapUrl())
                .speaker(w.getSpeaker())
                .status(w.getStatus())
                .userRegistrationStatus(userRegistrationStatus)
                .registered(userRegistrationStatus != null)
                .totalSlots(w.getTotalSlots())
                .remainingSlots(remainingSlots)
                .price(w.getPrice())
                .startTime(w.getStartTime())
                .endTime(w.getEndTime())
                .registrationStartTime(w.getRegistrationStartTime())
                .registrationEndTime(w.getRegistrationEndTime())
                .successfulCount(successfulCount)
                .build();
    }

    private Map<Long, String> getUserRegistrationStatuses(Long userId) {
        if (userId == null) {
            return Map.of();
        }

        return registrationRepository.findAllByUserIdWithWorkshop(userId)
                .stream()
                .filter(registration -> "PENDING".equals(registration.getStatus())
                        || "SUCCESS".equals(registration.getStatus()))
                .collect(Collectors.toMap(
                        registration -> registration.getWorkshop().getId(),
                        registration -> registration.getStatus(),
                        (existing, ignored) -> existing));
    }

    /**
     * Map a Registration entity (with User and optional CheckinRecord already
     * fetched) to a {@link WorkshopAttendanceResponse} DTO.
     */
    private WorkshopAttendanceResponse toAttendanceResponse(
            com.unihub.backend.entity.Registration r) {

        boolean checkedIn = r.getCheckinRecord() != null;
        return WorkshopAttendanceResponse.builder()
                .registrationId(r.getId())
                .userId(r.getUser().getId())
                .studentCode(r.getUser().getStudentCode())
                .fullName(r.getUser().getFullName())
                .email(r.getUser().getEmail())
                .phoneNumber(r.getUser().getPhoneNumber())
                .registrationStatus(r.getStatus())
                .registeredAt(r.getCreatedAt())
                .checkedIn(checkedIn)
                .checkedInAt(checkedIn ? r.getCheckinRecord().getScannedAt() : null)
                .build();
    }

    /**
     * Helper to get successful registration counts for a list of workshop IDs in a single query.
     *
     * @param workshopIds list of workshop IDs
     * @return map of workshop ID → successful registration count
     */
    private Map<Long, Long> getSuccessfulCountsForWorkshops(List<Long> workshopIds) {
        if (workshopIds == null || workshopIds.isEmpty()) {
            return Map.of();
        }

        List<Object[]> results = registrationRepository.countByWorkshopIdsAndStatus(workshopIds, "SUCCESS");

        return results.stream()
                .collect(Collectors.toMap(
                        row -> (Long) row[0],
                        row -> (Long) row[1]
                ));
    }
}
