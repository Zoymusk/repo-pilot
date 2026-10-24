package com.repopilot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GeminiEmbeddingServiceTest {

    @Test
    void capsRateLimitRetryWaitAtSixtySeconds() {
        assertEquals(Duration.ofSeconds(60),
                GeminiEmbeddingService.cappedRetryDelay(Duration.ofMinutes(5)));
        assertEquals(Duration.ofSeconds(10),
                GeminiEmbeddingService.cappedRetryDelay(Duration.ofSeconds(10)));
    }

    @Test
    void defaultsEmbeddingModelNameWhenUnset() {
        assertEquals("gemini-embedding-001", GeminiEmbeddingService.normalizeModelName(null));
        assertEquals("gemini-embedding-001", GeminiEmbeddingService.normalizeModelName(" "));
    }

    @Test
    void usesConfiguredEmbeddingModelInRequest() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(
                        "https://generativelanguage.googleapis.com/v1beta/models/custom-embedding:batchEmbedContents"))
                .andExpect(jsonPath("$.requests[0].model").value("models/custom-embedding"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess("""
                        {"embeddings":[{"values":[0.1,0.2]}]}
                        """, MediaType.APPLICATION_JSON));
        GeminiEmbeddingService service = new GeminiEmbeddingService(
                builder.build(),
                new ObjectMapper(),
                "custom-embedding",
                "test-api-key"
        );

        assertEquals(List.of(List.of(0.1, 0.2)), service.embedDocuments(List.of("text")));
        server.verify();
    }

    @Test
    void failsImmediatelyWithQuotaResetMessageForRetryDelaysOverNinetySeconds() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(
                        "https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-001:batchEmbedContents"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"error":{"details":[{"retryDelay":"91s"}]}}
                                """));
        GeminiEmbeddingService service = new GeminiEmbeddingService(
                builder.build(),
                new ObjectMapper(),
                "gemini-embedding-001",
                "test-api-key"
        );

        GeminiEmbeddingService.DailyEmbeddingQuotaExceededException exception = assertThrows(
                GeminiEmbeddingService.DailyEmbeddingQuotaExceededException.class,
                () -> service.embedDocuments(List.of("text"))
        );

        assertTrue(exception.getMessage().contains("daily embedding quota is used up"));
        assertTrue(exception.getMessage().contains("1 minute and 31 seconds"));
        server.verify();
    }

    @Test
    void cachesDocumentEmbeddingsForSubsequentRequestsAndDuplicateTexts() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(
                        "https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-001:batchEmbedContents"))
                .andExpect(jsonPath("$.requests.length()").value(1))
                .andRespond(withSuccess("""
                        {"embeddings":[{"values":[0.1,0.2]}]}
                        """, MediaType.APPLICATION_JSON));
        GeminiEmbeddingService service = new GeminiEmbeddingService(
                builder.build(),
                new ObjectMapper(),
                "gemini-embedding-001",
                "test-api-key"
        );

        List<List<Double>> first = service.embedDocuments(List.of("same text", "same text"));
        List<List<Double>> second = service.embedDocuments(List.of("same text"));

        assertEquals(List.of(List.of(0.1, 0.2), List.of(0.1, 0.2)), first);
        assertEquals(List.of(List.of(0.1, 0.2)), second);
        server.verify();
    }

    @Test
    void retriesTransientNetworkErrorsWithGrowingWaits() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String endpoint =
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-001:batchEmbedContents";
        server.expect(requestTo(endpoint))
                .andRespond(request -> {
                    throw new ResourceAccessException("Connection reset");
                });
        server.expect(requestTo(endpoint))
                .andRespond(request -> {
                    throw new ResourceAccessException("SSL handshake failure");
                });
        server.expect(requestTo(endpoint))
                .andRespond(withSuccess("""
                        {"embeddings":[{"values":[0.5,0.6]}]}
                        """, MediaType.APPLICATION_JSON));
        List<Duration> waits = new ArrayList<>();
        GeminiEmbeddingService service = new GeminiEmbeddingService(
                builder.build(),
                new ObjectMapper(),
                "gemini-embedding-001",
                "test-api-key"
        ) {
            @Override
            void pause(Duration duration) {
                waits.add(duration);
            }
        };

        assertEquals(List.of(List.of(0.5, 0.6)), service.embedDocuments(List.of("retry me")));
        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2)), waits);
        server.verify();
    }

    @Test
    void failsAfterFourConsecutiveTransientNetworkErrors() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String endpoint =
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-001:batchEmbedContents";
        for (int attempt = 0; attempt < 4; attempt++) {
            server.expect(requestTo(endpoint))
                    .andRespond(request -> {
                        throw new ResourceAccessException("Connection reset");
                    });
        }
        List<Duration> waits = new ArrayList<>();
        GeminiEmbeddingService service = new GeminiEmbeddingService(
                builder.build(),
                new ObjectMapper(),
                "gemini-embedding-001",
                "test-api-key"
        ) {
            @Override
            void pause(Duration duration) {
                waits.add(duration);
            }
        };

        assertThrows(ResourceAccessException.class, () -> service.embedDocuments(List.of("retry me")));
        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4)), waits);
        server.verify();
    }
}
