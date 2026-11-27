package com.unihub.backend.service;

import com.unihub.backend.dto.ai.AiSummaryResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "ai.documents.worker-enabled", matchIfMissing = true)
public class WorkshopAiService {
    private final WorkshopAiJobs jobs;
    private final RestTemplate restTemplate;

    @Value("${ai.service.url:http://localhost:8000}")
    private String aiServiceUrl;

    @Scheduled(fixedDelay = 1000, scheduler = "aiDocumentScheduler")
    public void processNextDocument() {
        jobs.claim().ifPresent(this::process);
    }

    private void process(WorkshopAiJobs.Job job) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("workshop_id", job.workshopId());
        body.add("file", new ByteArrayResource(job.document()) {
            @Override
            public String getFilename() {
                return "document.pdf";
            }
        });

        AiSummaryResponse result;
        try {
            var response = restTemplate.postForEntity(aiServiceUrl + "/summarize",
                    new HttpEntity<>(body, headers), AiSummaryResponse.class);
            result = response.getBody();
            if (!response.getStatusCode().is2xxSuccessful() || result == null
                    || !Objects.equals(result.getWorkshop_id(), job.workshopId())) {
                jobs.fail(job, true, "Invalid response from AI service");
                return;
            }
        } catch (RestClientResponseException error) {
            int status = error.getStatusCode().value();
            boolean retry = status >= 500 || status == 429 || status == 408;
            jobs.fail(job, retry, status == 400 ? "Could not extract text from PDF file." : "AI service request failed");
            return;
        } catch (RestClientException error) {
            jobs.fail(job, true, "AI service is temporarily unavailable");
            return;
        }

        String summary = boundedText(result.getSummary(), 12_000);
        if (summary == null) {
            jobs.fail(job, true, "AI service returned an empty summary");
            return;
        }
        jobs.complete(job, summary, boundedText(result.getSpeaker(), 255));
        log.info("Processed AI document for workshop {} generation {}", job.workshopId(), job.generation());
    }

    private static String boundedText(String text, int maxCodePoints) {
        if (text == null) {
            return null;
        }
        String clean = text.replace("\u0000", "").strip();
        int length = clean.codePointCount(0, clean.length());
        return length == 0 ? null : clean.substring(0, clean.offsetByCodePoints(0, Math.min(length, maxCodePoints)));
    }
}
