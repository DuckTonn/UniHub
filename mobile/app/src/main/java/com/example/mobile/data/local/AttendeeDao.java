package com.example.mobile.data.local;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

import java.util.HashMap;
import java.util.List;

@Dao
public interface AttendeeDao {

    @Query("SELECT * FROM attendees WHERE workshop_id = :workshopId")
    List<AttendeeEntity> getAttendeesByWorkshop(long workshopId);

    @Query("SELECT * FROM attendees WHERE qr_code = :qrCode AND workshop_id = :workshopId LIMIT 1")
    AttendeeEntity getAttendeeByQrCode(String qrCode, long workshopId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<AttendeeEntity> attendees);

    @Query("UPDATE attendees SET is_checked_in = 1, scanned_at = :scannedAt, is_synced = 0 "
            + "WHERE qr_code = :qrCode AND workshop_id = :workshopId AND is_checked_in = 0")
    int recordScan(String qrCode, long workshopId, String scannedAt);

    @Query("UPDATE attendees SET is_synced = 1 WHERE qr_code = :qrCode AND workshop_id = :workshopId "
            + "AND is_checked_in = 1 AND scanned_at = :scannedAt")
    void acknowledgeScan(String qrCode, long workshopId, String scannedAt);

    @Query("SELECT * FROM attendees WHERE is_checked_in = 1 AND is_synced = 0 AND workshop_id = :workshopId")
    List<AttendeeEntity> getUnsyncedCheckins(long workshopId);

    @Query("SELECT * FROM attendees WHERE is_checked_in = 1 AND is_synced = 0")
    List<AttendeeEntity> getAllUnsyncedCheckins();

    @Query("DELETE FROM attendees WHERE workshop_id = :workshopId AND (is_checked_in = 0 OR is_synced = 1)")
    void deleteReplaceableAttendees(long workshopId);

    @Transaction
    default void refreshRoster(long workshopId, List<AttendeeEntity> roster) {
        var checkedIn = new HashMap<String, AttendeeEntity>();
        for (AttendeeEntity attendee : getAttendeesByWorkshop(workshopId)) {
            if (attendee.isCheckedIn()) {
                checkedIn.put(attendee.getQrCode(), attendee);
            }
        }
        deleteReplaceableAttendees(workshopId);
        for (AttendeeEntity attendee : roster) {
            var local = checkedIn.get(attendee.getQrCode());
            if (local != null && !attendee.isCheckedIn()) {
                attendee.setCheckedIn(true);
                attendee.setScannedAt(local.getScannedAt());
                attendee.setSynced(local.isSynced());
            }
        }
        insertAll(roster);
    }
}
