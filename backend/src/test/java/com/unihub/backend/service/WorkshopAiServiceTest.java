package com.unihub.backend.service;

import com.unihub.backend.dto.ai.AiSummaryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkshopAiServiceTest {
    private static final String ENDPOINT = "http://ai-service:8000/summarize";
    private final WorkshopAiJobs.Job job = new WorkshopAiJobs.Job(7, 1, 1, new byte[] {1, 2});

    @Mock private WorkshopAiJobs jobs;
    @Mock private RestTemplate restTemplate;
    @InjectMocks private WorkshopAiService worker;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(worker, "aiServiceUrl", "http://ai-service:8000");
        when(jobs.claim()).thenReturn(Optional.of(job));
    }

    @Test
    void matchingWorkshopUpdatesSummary() {
        var response = new AiSummaryResponse();
        response.setWorkshop_id(job.workshopId());
        response.setSummary("AI summary");
        when(restTemplate.postForEntity(eq(ENDPOINT), any(HttpEntity.class), eq(AiSummaryResponse.class)))
                .thenReturn(ResponseEntity.ok(response));

        worker.processNextDocument();

        verify(jobs).complete(job, "AI summary", null);
    }

    @Test
    void mismatchedWorkshopIsNeverUpdated() {
        var response = new AiSummaryResponse();
        response.setWorkshop_id(13L);
        response.setSummary("AI summary");
        when(restTemplate.postForEntity(eq(ENDPOINT), any(HttpEntity.class), eq(AiSummaryResponse.class)))
                .thenReturn(ResponseEntity.ok(response));

        worker.processNextDocument();

        verify(jobs).fail(job, true, "Invalid response from AI service");
    }

    @Test
    void timeoutsAreRetried() {
        when(restTemplate.postForEntity(eq(ENDPOINT), any(HttpEntity.class), eq(AiSummaryResponse.class)))
                .thenThrow(new ResourceAccessException("Connection timeout"));

        worker.processNextDocument();

        verify(jobs).fail(job, true, "AI service is temporarily unavailable");
    }

    @Test
    void invalidPdfFailsWithoutRetry() {
        when(restTemplate.postForEntity(eq(ENDPOINT), any(HttpEntity.class), eq(AiSummaryResponse.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST));

        worker.processNextDocument();

        verify(jobs).fail(job, false, "Could not extract text from PDF file.");
    }
}
