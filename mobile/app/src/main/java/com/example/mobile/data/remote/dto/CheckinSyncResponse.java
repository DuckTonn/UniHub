package com.example.mobile.data.remote.dto;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CheckinSyncResponse {
    private int totalReceived;
    private int successCount;
    private int duplicateCount;
    private int failedCount;
    private List<FailedEntry> failures;

    public int getTotalReceived() { return totalReceived; }
    public void setTotalReceived(int totalReceived) { this.totalReceived = totalReceived; }

    public int getSuccessCount() { return successCount; }
    public void setSuccessCount(int successCount) { this.successCount = successCount; }

    public int getDuplicateCount() { return duplicateCount; }
    public void setDuplicateCount(int duplicateCount) { this.duplicateCount = duplicateCount; }

    public int getFailedCount() { return failedCount; }
    public void setFailedCount(int failedCount) { this.failedCount = failedCount; }

    public Set<String> acknowledgedCodes(Set<String> submitted) {
        var acknowledged = new HashSet<>(submitted);
        if (failedCount > 0 && failures == null) {
            throw new IllegalArgumentException("Missing failed check-ins");
        }
        if (failures != null) {
            for (FailedEntry failure : failures) {
                if (failure == null || !acknowledged.remove(failure.qrCode)) {
                    throw new IllegalArgumentException("Unknown or repeated failed check-in");
                }
            }
        }
        if (totalReceived != submitted.size() || submitted.size() - acknowledged.size() != failedCount
                || successCount < 0 || duplicateCount < 0 || successCount + duplicateCount != acknowledged.size()) {
            throw new IllegalArgumentException("Check-in response counts do not match the batch");
        }
        return acknowledged;
    }

    public static class FailedEntry {
        private String qrCode;
    }
}
