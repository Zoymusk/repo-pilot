package com.repopilot.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MethodChunkerServiceTest {

    private final MethodChunkerService chunkerService = new MethodChunkerService();

    @Test
    void chunksMethodsWithClassNamesAndInclusiveSourceLines() {
        String source = """
                package example;
                public class Sample {
                    public void first() {
                        int value = 1;
                    }

                    private int second() {
                        return 2;
                    }

                    class Nested {
                        void nestedMethod() {
                        }
                    }
                }
                """;

        List<MethodChunkerService.MethodChunk> chunks = chunkerService.chunk(source, "src/Sample.java");

        assertEquals(5, chunks.size());
        assertEquals(new MethodChunkerService.MethodChunk(
                "Sample",
                "first",
                "src/Sample.java",
                3,
                5,
                "    public void first() {\n        int value = 1;\n    }"
        ), chunks.get(0));
        assertEquals("second", chunks.get(1).methodName());
        assertEquals(7, chunks.get(1).startLine());
        assertEquals(9, chunks.get(1).endLine());
        assertEquals("Nested", chunks.get(2).className());
        assertEquals("nestedMethod", chunks.get(2).methodName());
        assertEquals("overview", chunks.get(3).methodName());
        assertTrue(chunks.get(3).content().contains("Class overview: Sample"));
        assertTrue(chunks.get(3).content().contains("Method signatures:\n- void first()"));
        assertEquals("overview", chunks.get(4).methodName());
        assertTrue(chunks.get(4).content().contains("Class overview: Nested"));
    }

    @Test
    void chunksMethodsInJavaRecord() {
        String source = """
                package example;
                public record SampleRecord(String name) {
                    public String displayName() {
                        return name.toUpperCase();
                    }
                }
                """;

        List<MethodChunkerService.MethodChunk> chunks =
                chunkerService.chunk(source, "src/SampleRecord.java");

        assertEquals(2, chunks.size());
        assertEquals(new MethodChunkerService.MethodChunk(
                "SampleRecord",
                "displayName",
                "src/SampleRecord.java",
                3,
                5,
                "    public String displayName() {\n        return name.toUpperCase();\n    }"
        ), chunks.get(0));
        assertEquals("overview", chunks.get(1).methodName());
        assertTrue(chunks.get(1).content().contains("String name"));
        assertTrue(chunks.get(1).content().contains("String displayName()"));
    }

    @Test
    void parsesJava21RecordTextBlockAndInstanceofPattern() {
        String source = """
                package example;
                public record SampleRecord(String value) {
                    public String describe(Object candidate) {
                        String text = \"\"\"
                                sample text
                                \"\"\";
                        if (candidate instanceof String matched) {
                            return text + matched;
                        }
                        return text;
                    }
                }
                """;

        List<MethodChunkerService.MethodChunk> chunks =
                chunkerService.chunk(source, "src/SampleRecord.java");

        assertEquals(2, chunks.size());
        assertEquals("SampleRecord", chunks.get(0).className());
        assertEquals("describe", chunks.get(0).methodName());
        assertTrue(chunks.get(0).content().contains("instanceof String matched"));
        assertTrue(chunks.get(0).content().contains("\"\"\""));
        assertEquals("overview", chunks.get(1).methodName());
    }

    @Test
    void stopsChunkingWhenTheConfiguredChunkLimitWouldBeExceeded() {
        String source = """
                class Sample {
                    void first() {}
                    void second() {}
                }
                """;

        MethodChunkerService.ChunkLimitExceededException exception = assertThrows(
                MethodChunkerService.ChunkLimitExceededException.class,
                () -> chunkerService.chunk(source, "src/Sample.java", 2)
        );

        assertTrue(exception.getMessage().contains("limited to 2 chunks"));
    }
}
