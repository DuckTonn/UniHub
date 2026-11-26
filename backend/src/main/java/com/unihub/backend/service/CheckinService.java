package com.unihub.backend.service;

import com.unihub.backend.dto.AttendeeResponse;
import com.unihub.backend.dto.CheckinSyncRequest;
import com.unihub.backend.dto.CheckinSyncResponse;
import com.unihub.backend.entity.CheckinRecord;
import com.unihub.backend.entity.Registration;
import com.unihub.backend.entity.Workshop;
import com.unihub.backend.exception.ResourceNotFoundException;
import com.unihub.backend.repository.CheckinRecordRepository;
import com.unihub.backend.repository.RegistrationRepository;
import com.unihub.backend.repository.WorkshopRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class CheckinService {

    private final WorkshopRepository workshopRepository;
    private final RegistrationRepository registrationRepository;
    private final CheckinRecordRepository checkinRecordRepository;

    @Transactional(readOnly = true)
    public List<AttendeeResponse> getWorkshopAttendees(Long workshopId) {
        Workshop workshop = workshopRepository.findById(workshopId)
                .orElseThrow(() -> new ResourceNotFoundException("Workshop not found with id " + workshopId));

        if (workshop.getRegistrationEndTime() != null && LocalDateTime.now().isBefore(workshop.getRegistrationEndTime())) {
            throw new com.unihub.backend.exception.ForbiddenOperationException("Cannot download attendees while registration is still open.");
        }

        List<Registration> validRegistrations = registrationRepository.findConfirmedAttendees(workshopId);
        Map<Long, CheckinRecord> checkinMap = checkinRecordRepository.findByRegistrationWorkshopId(workshopId).stream()
                .collect(Collectors.toMap(CheckinRecord::getRegistrationId, c -> c));
        
        return validRegistrations.stream()
                .map(r -> {
                    CheckinRecord record = checkinMap.get(r.getId());
                    return AttendeeResponse.builder()
                            .qrCode(r.getQrCode())
                            .registrationId(r.getId())
                            .studentName(r.getUser().getFullName())
                            .studentCode(r.getUser().getStudentCode())
                            .isCheckedIn(record != null)
                            .scannedAt(record != null ? record.getScannedAt().toString() : null)
                            .build();
                })
                .collect(Collectors.toList());
    }

    @Transactional
    public CheckinSyncResponse syncCheckins(CheckinSyncRequest request) {
        Long workshopId = request.getWorkshopId();
        
        // The same lock protects registration status changes and workshop cancellation.
        workshopRepository.findByIdForUpdate(workshopId)
                .orElseThrow(() -> new ResourceNotFoundException("Workshop not found with id " + workshopId));

        CheckinSyncResponse response = new CheckinSyncResponse();
        response.setTotalReceived(request.getRecords().size());

        var codes = request.getRecords().stream().map(CheckinSyncRequest.CheckinEntry::getQrCode).distinct().toList();
        Map<String, Registration> qrToRegistrationMap = registrationRepository.findConfirmedTickets(workshopId, codes)
                .stream().collect(Collectors.toMap(Registration::getQrCode, r -> r));
        LocalDateTime syncedAt = LocalDateTime.now();

        for (CheckinSyncRequest.CheckinEntry entry : request.getRecords()) {
            Registration registration = qrToRegistrationMap.get(entry.getQrCode());

            if (registration == null) {
                response.setFailedCount(response.getFailedCount() + 1);
                response.getFailures().add(CheckinSyncResponse.FailedEntry.builder()
                        .qrCode(entry.getQrCode())
                        .reason("QR Code not found, not SUCCESS, or does not belong to this workshop")
                        .build());
                continue;
            }

            int inserted = checkinRecordRepository.insertOnConflictDoNothing(
                    registration.getId(), entry.getScannedAt(), syncedAt);
            response.setSuccessCount(response.getSuccessCount() + inserted);
            response.setDuplicateCount(response.getDuplicateCount() + 1 - inserted);
        }

        log.info("Synced check-ins for workshop {}: {} total, {} success, {} duplicates, {} failed",
                workshopId, response.getTotalReceived(), response.getSuccessCount(),
                response.getDuplicateCount(), response.getFailedCount());

        return response;
    }
}
