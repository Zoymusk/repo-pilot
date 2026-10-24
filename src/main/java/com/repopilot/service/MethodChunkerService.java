package com.repopilot.service;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParseResult;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class MethodChunkerService {

    private static final Logger logger = LoggerFactory.getLogger(MethodChunkerService.class);
    private static final String OVERVIEW_METHOD_NAME = "overview";

    public List<MethodChunk> chunk(String source, String filePath) {
        return chunk(source, filePath, Integer.MAX_VALUE);
    }

    public List<MethodChunk> chunk(String source, String filePath, int maxChunks) {
        final CompilationUnit compilationUnit;
        try {
            JavaParser parser = new JavaParser(new ParserConfiguration()
                    .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
            ParseResult<CompilationUnit> parseResult = parser.parse(source);
            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                throw new ParseProblemException(parseResult.getProblems());
            }
            compilationUnit = parseResult.getResult().orElseThrow();
        } catch (ParseProblemException exception) {
            logger.warn("Skipping Java source file {} because parsing failed", filePath, exception);
            return List.of();
        }

        List<String> lines = source.lines().toList();
        List<MethodChunk> chunks = new ArrayList<>();
        for (MethodDeclaration method : compilationUnit.findAll(MethodDeclaration.class)) {
            ensureChunkCapacity(chunks, maxChunks, filePath);
            chunks.add(toChunk(method, lines, filePath));
        }
        for (TypeDeclaration<?> type : compilationUnit.findAll(TypeDeclaration.class)) {
            ensureChunkCapacity(chunks, maxChunks, filePath);
            chunks.add(toOverviewChunk(type, filePath));
        }
        return List.copyOf(chunks);
    }

    private void ensureChunkCapacity(List<MethodChunk> chunks, int maxChunks, String filePath) {
        if (chunks.size() >= maxChunks) {
            throw new ChunkLimitExceededException(filePath, maxChunks);
        }
    }

    private MethodChunk toOverviewChunk(TypeDeclaration<?> type, String filePath) {
        Range range = type.getRange()
                .orElseThrow(() -> new IllegalStateException("Parsed type has no source range: " + type.getNameAsString()));
        List<String> fields = new ArrayList<>();
        type.getMembers().stream()
                .filter(FieldDeclaration.class::isInstance)
                .map(FieldDeclaration.class::cast)
                .map(FieldDeclaration::toString)
                .map(String::strip)
                .forEach(fields::add);
        if (type instanceof RecordDeclaration record) {
            record.getParameters().stream()
                    .map(Object::toString)
                    .forEach(fields::add);
        }

        List<String> methodSignatures = new ArrayList<>();
        type.getMembers().stream()
                .filter(MethodDeclaration.class::isInstance)
                .map(MethodDeclaration.class::cast)
                .map(method -> method.getDeclarationAsString(false, false, true))
                .forEach(methodSignatures::add);
        type.getMembers().stream()
                .filter(ConstructorDeclaration.class::isInstance)
                .map(ConstructorDeclaration.class::cast)
                .map(constructor -> constructor.getDeclarationAsString(false, false, true))
                .forEach(methodSignatures::add);

        StringBuilder content = new StringBuilder("Class overview: ").append(type.getNameAsString());
        content.append("\nFields:\n");
        fields.forEach(field -> content.append("- ").append(field).append('\n'));
        content.append("Method signatures:\n");
        methodSignatures.forEach(signature -> content.append("- ").append(signature).append('\n'));
        return new MethodChunk(
                type.getNameAsString(),
                OVERVIEW_METHOD_NAME,
                filePath,
                range.begin.line,
                range.end.line,
                content.toString().stripTrailing()
        );
    }

    private MethodChunk toChunk(MethodDeclaration method, List<String> lines, String filePath) {
        Range range = method.getRange()
                .orElseThrow(() -> new IllegalStateException("Parsed method has no source range: " + method.getNameAsString()));
        String className = method.findAncestor(TypeDeclaration.class)
                .map(TypeDeclaration::getNameAsString)
                .orElse("Anonymous");

        int startLine = range.begin.line;
        int endLine = range.end.line;
        String content = String.join("\n", lines.subList(startLine - 1, endLine));
        return new MethodChunk(className, method.getNameAsString(), filePath, startLine, endLine, content);
    }

    public record MethodChunk(
            String className,
            String methodName,
            String filePath,
            int startLine,
            int endLine,
            String content
    ) {
    }

    public static class ChunkLimitExceededException extends RuntimeException {
        public ChunkLimitExceededException(String filePath, int maxChunks) {
            super("Indexing is limited to " + maxChunks + " chunks; exceeded while processing " + filePath);
        }
    }
}
