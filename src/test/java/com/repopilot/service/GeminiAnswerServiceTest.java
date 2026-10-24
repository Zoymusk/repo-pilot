package com.repopilot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.service.GeminiAnswerService.Answer;
import com.repopilot.service.InMemoryVectorStore.SearchResult;
import com.repopilot.service.MethodChunkerService.MethodChunk;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.http.HttpMethod.POST;

class GeminiAnswerServiceTest {

    @Test
    void retriesRateLimitAndParsesMockedGeminiAnswer() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        server.expect(requestTo(
                        "https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(
                        "https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent"))
                .andExpect(method(POST))
                .andExpect(header("x-goog-api-key", "test-key"))
                .andExpect(jsonPath("$.generationConfig.responseMimeType").value("application/json"))
                .andExpect(jsonPath("$.generationConfig.responseSchema.type").value("OBJECT"))
                .andExpect(jsonPath(
                        "$.generationConfig.responseSchema.properties.prPlan.properties.steps.items.properties.testsToAdd.type"
                ).value("ARRAY"))
                .andExpect(jsonPath(
                        "$.generationConfig.responseSchema.properties.prPlan.properties.steps.items.properties.filesToCreate.type"
                ).value("ARRAY"))
                .andExpect(jsonPath(
                        "$.generationConfig.responseSchema.properties.prPlan.properties.steps.items.properties.filesToModify.type"
                ).value("ARRAY"))
                .andExpect(jsonPath(
                        "$.generationConfig.responseSchema.properties.prPlan.properties.steps.items.properties.referenceFiles.type"
                ).value("ARRAY"))
                .andRespond(withSuccess("""
                        {
                          "candidates": [{
                            "content": {
                              "parts": [{
                                "text": "{\\"explanation\\":\\"The entry point starts the application.\\",\\"citations\\":[{\\"filePath\\":\\"src/main/java/example/App.java\\",\\"startLine\\":4,\\"endLine\\":6}],\\"prPlan\\":{\\"steps\\":[{\\"description\\":\\"Update startup behavior.\\",\\"filesToCreate\\":[\\"src/main/java/example/NewStartup.java\\"],\\"filesToModify\\":[\\"src/main/java/example/App.java\\"],\\"referenceFiles\\":[\\"src/main/java/example/App.java\\"],\\"testsToAdd\\":[{\\"testClass\\":\\"AppTest\\",\\"checks\\":\\"verifies startup invokes the application runner\\"}]}]}}"
                              }]
                            }
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        GeminiAnswerService answerService = answerService(restClientBuilder, "gemini-test", "");
        MethodChunk chunk = new MethodChunk(
                "App",
                "main",
                "src/main/java/example/App.java",
                4,
                6,
                "public static void main(String[] args) {}"
        );

        Answer answer = answerService.answer(
                "Where does the app start?",
                null,
                List.of(new SearchResult(chunk, 0.98))
        );

        assertEquals("The entry point starts the application.", answer.explanation());
        assertEquals(List.of(new GeminiAnswerService.Citation(
                "src/main/java/example/App.java", 4, 6)), answer.citations());
        assertEquals(List.of(new GeminiAnswerService.TestToAdd(
                "AppTest", "verifies startup invokes the application runner")),
                answer.prPlan().steps().get(0).testsToAdd());
        assertEquals(List.of("src/main/java/example/NewStartup.java"),
                answer.prPlan().steps().get(0).filesToCreate());
        server.verify();
    }

    @Test
    void acceptsMissingTestsAndIgnoresUnknownFields() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        server.expect(requestTo(
                        "https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent"))
                .andRespond(withSuccess("""
                        {
                          "candidates": [{
                            "content": {
                              "parts": [{
                                "text": "{\\"explanation\\":\\"Answer.\\",\\"extra\\":true,\\"citations\\":[],\\"prPlan\\":{\\"steps\\":[{\\"description\\":\\"Make a change.\\",\\"filesToCreate\\":[],\\"filesToModify\\":[],\\"referenceFiles\\":[],\\"unexpected\\":\\"ignored\\"}]}}"
                              }]
                            }
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));
        GeminiAnswerService answerService = answerService(restClientBuilder, "gemini-test", "");

        Answer answer = answerService.answer("Question?", null, List.of());

        assertEquals(List.of(), answer.prPlan().steps().get(0).testsToAdd());
        server.verify();
    }

    @Test
    void rejectsFilesToModifyThatWereNotRetrieved() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent";
        String invalidAnswer = """
                {
                  "candidates": [{
                    "content": {
                      "parts": [{
                        "text": "{\\"explanation\\":\\"Answer.\\",\\"citations\\":[],\\"prPlan\\":{\\"steps\\":[{\\"description\\":\\"Change it.\\",\\"filesToCreate\\":[],\\"filesToModify\\":[\\"not-retrieved.java\\"],\\"referenceFiles\\":[],\\"testsToAdd\\":[]}]}}"
                      }]
                    }
                  }]
                }
                """;
        server.expect(requestTo(endpoint)).andRespond(withSuccess(invalidAnswer, MediaType.APPLICATION_JSON));
        server.expect(requestTo(endpoint)).andRespond(withSuccess(invalidAnswer, MediaType.APPLICATION_JSON));
        GeminiAnswerService answerService = answerService(restClientBuilder, "gemini-test", "");

        assertThrows(
                GeminiAnswerService.InvalidGeminiResponseException.class,
                () -> answerService.answer("Question?", null, List.of())
        );

        server.verify();
    }

    @Test
    void rejectsReferenceFilesThatWereNotRetrieved() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent";
        String invalidAnswer = """
                {
                  "candidates": [{
                    "content": {
                      "parts": [{
                        "text": "{\\"explanation\\":\\"Answer.\\",\\"citations\\":[],\\"prPlan\\":{\\"steps\\":[{\\"description\\":\\"Use it.\\",\\"filesToCreate\\":[],\\"filesToModify\\":[],\\"referenceFiles\\":[\\"not-retrieved.java\\"],\\"testsToAdd\\":[]}]}}"
                      }]
                    }
                  }]
                }
                """;
        server.expect(requestTo(endpoint)).andRespond(withSuccess(invalidAnswer, MediaType.APPLICATION_JSON));
        server.expect(requestTo(endpoint)).andRespond(withSuccess(invalidAnswer, MediaType.APPLICATION_JSON));
        GeminiAnswerService answerService = answerService(restClientBuilder, "gemini-test", "");

        assertThrows(
                GeminiAnswerService.InvalidGeminiResponseException.class,
                () -> answerService.answer("Question?", null, List.of())
        );

        server.verify();
    }

    @Test
    void retriesOnceWhenAnswerValidationFails() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent";
        server.expect(requestTo(endpoint))
                .andRespond(withSuccess("""
                        {
                          "candidates": [{
                            "content": {
                              "parts": [{"text": "{\\"explanation\\":\\"Missing required fields.\\"}"}]
                            }
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(endpoint))
                .andRespond(withSuccess("""
                        {
                          "candidates": [{
                            "content": {
                              "parts": [{
                                "text": "{\\"explanation\\":\\"Recovered answer.\\",\\"citations\\":[],\\"prPlan\\":{\\"steps\\":[]}}"
                              }]
                            }
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));
        GeminiAnswerService answerService = answerService(restClientBuilder, "gemini-test", "");

        Answer answer = answerService.answer("Question?", null, List.of());

        assertEquals("Recovered answer.", answer.explanation());
        server.verify();
    }

    @Test
    void retries503FiveTimesThenUsesFallbackModelOnce() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        String mainEndpoint =
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent";
        String fallbackEndpoint =
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-fallback:generateContent";
        for (int attempt = 0; attempt < 5; attempt++) {
            server.expect(requestTo(mainEndpoint))
                    .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        }
        server.expect(requestTo(fallbackEndpoint))
                .andRespond(withSuccess("""
                        {
                          "candidates": [{
                            "content": {
                              "parts": [{"text": "{\\"explanation\\":\\"Fallback answer.\\",\\"citations\\":[],\\"prPlan\\":{\\"steps\\":[]}}"}]
                            }
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));
        GeminiAnswerService answerService =
                answerService(restClientBuilder, "gemini-test", "gemini-fallback");

        Answer answer = answerService.answer("Question?", null, List.of());

        assertEquals("Fallback answer.", answer.explanation());
        server.verify();
    }

    @Test
    void throwsBusyExceptionWhenMainRetriesAndFallbackFail() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        String mainEndpoint =
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent";
        String fallbackEndpoint =
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-fallback:generateContent";
        for (int attempt = 0; attempt < 5; attempt++) {
            server.expect(requestTo(mainEndpoint))
                    .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        }
        server.expect(requestTo(fallbackEndpoint))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        GeminiAnswerService answerService =
                answerService(restClientBuilder, "gemini-test", "gemini-fallback");

        GeminiAnswerService.GeminiModelBusyException exception = assertThrows(
                GeminiAnswerService.GeminiModelBusyException.class,
                () -> answerService.answer("Question?", null, List.of())
        );

        assertEquals("The AI model is busy. Please try again in a minute.", exception.getMessage());
        server.verify();
    }

    @Test
    void doesNotRetryOrFallbackForOtherServerErrors() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        server.expect(requestTo(
                        "https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        GeminiAnswerService answerService =
                answerService(restClientBuilder, "gemini-test", "gemini-fallback");

        assertThrows(
                org.springframework.web.client.RestClientResponseException.class,
                () -> answerService.answer("Question?", null, List.of())
        );
        server.verify();
    }

    private GeminiAnswerService answerService(
            RestClient.Builder restClientBuilder,
            String model,
            String fallbackModel
    ) {
        return new GeminiAnswerService(
                restClientBuilder.build(),
                new ObjectMapper(),
                model,
                fallbackModel,
                "test-key"
        ) {
            @Override
            void pauseBeforeRetry(int attempt) {
            }
        };
    }
}
