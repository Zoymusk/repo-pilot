package com.repopilot.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

        assertEquals(3, chunks.size());
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

        assertEquals(1, chunks.size());
        assertEquals(new MethodChunkerService.MethodChunk(
                "SampleRecord",
                "displayName",
                "src/SampleRecord.java",
                3,
                5,
                "    public String displayName() {\n        return name.toUpperCase();\n    }"
        ), chunks.get(0));
    }
}
