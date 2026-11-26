package com.example.mobile;

import com.example.mobile.data.remote.dto.CheckinSyncResponse;
import com.google.gson.Gson;
import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class CheckinSyncResponseTest {
    private final Gson gson = new Gson();

    @Test
    public void acknowledgesSuccessfulAndDuplicateScansOnly() {
        var response = gson.fromJson("{\"totalReceived\":3,\"successCount\":1,\"duplicateCount\":1,"
                + "\"failedCount\":1,\"failures\":[{\"qrCode\":\"invalid\",\"reason\":\"wrong workshop\"}]}",
                CheckinSyncResponse.class);
        assertEquals(Set.of("first", "second"), response.acknowledgedCodes(Set.of("first", "second", "invalid")));
    }

    @Test
    public void inconsistentResponseCannotAcknowledgeScans() {
        var response = gson.fromJson("{\"totalReceived\":2,\"successCount\":1,\"failedCount\":1,"
                + "\"failures\":[{\"qrCode\":\"unknown\"}]}", CheckinSyncResponse.class);
        assertThrows(IllegalArgumentException.class, () -> response.acknowledgedCodes(Set.of("first", "second")));
    }
}
