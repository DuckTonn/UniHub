package com.example.mobile.sync;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.example.mobile.data.local.AppDatabase;
import com.example.mobile.data.local.AttendeeDao;
import com.example.mobile.data.local.AttendeeEntity;
import com.example.mobile.data.remote.ApiService;
import com.example.mobile.data.remote.RetrofitClient;
import com.example.mobile.data.remote.dto.CheckinSyncRequest;
import com.example.mobile.data.remote.dto.CheckinSyncResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import retrofit2.Response;

public class CheckinSyncWorker extends Worker {

    private static final String TAG = "CheckinSyncWorker";

    public CheckinSyncWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "Starting background sync...");
        AppDatabase db = AppDatabase.getDatabase(getApplicationContext());
        AttendeeDao dao = db.attendeeDao();
        ApiService apiService = RetrofitClient.getApiService(getApplicationContext());

        List<AttendeeEntity> unsynced = dao.getAllUnsyncedCheckins();
        if (unsynced.isEmpty()) {
            Log.d(TAG, "No records to sync.");
            return Result.success();
        }

        // Group by workshopId
        Map<Long, List<AttendeeEntity>> grouped = new HashMap<>();
        for (AttendeeEntity entity : unsynced) {
            long wId = entity.getWorkshopId();
            if (!grouped.containsKey(wId)) {
                grouped.put(wId, new ArrayList<>());
            }
            grouped.get(wId).add(entity);
        }

        boolean rejected = false;
        boolean retryable = false;

        for (Map.Entry<Long, List<AttendeeEntity>> entry : grouped.entrySet()) {
            Long workshopId = entry.getKey();
            List<AttendeeEntity> entities = entry.getValue();

            List<CheckinSyncRequest.CheckinEntry> records = new ArrayList<>();
            for (AttendeeEntity e : entities) {
                records.add(new CheckinSyncRequest.CheckinEntry(e.getQrCode(), e.getScannedAt()));
            }

            CheckinSyncRequest request = new CheckinSyncRequest(workshopId, records);
            try {
                Response<CheckinSyncResponse> response = apiService.syncCheckins(request).execute();
                if (response.isSuccessful() && response.body() != null) {
                    var submitted = entities.stream().map(AttendeeEntity::getQrCode).collect(Collectors.toSet());
                    var acknowledged = response.body().acknowledgedCodes(submitted);
                    for (AttendeeEntity e : entities) {
                        if (acknowledged.contains(e.getQrCode())) {
                            dao.acknowledgeScan(e.getQrCode(), workshopId, e.getScannedAt());
                        }
                    }
                    rejected |= acknowledged.size() < submitted.size();
                } else {
                    Log.e(TAG, "Sync failed for workshop " + workshopId + ": " + response.code());
                    if (response.code() == 429 || response.code() >= 500 || response.isSuccessful()) {
                        retryable = true;
                    } else {
                        rejected = true;
                    }
                }
            } catch (IOException | IllegalArgumentException exception) {
                Log.w(TAG, "Could not confirm check-in batch for workshop " + workshopId);
                retryable = true;
            }
        }

        if (retryable && getRunAttemptCount() < 4) {
            return Result.retry();
        }
        return rejected || retryable ? Result.failure() : Result.success();
    }
}
