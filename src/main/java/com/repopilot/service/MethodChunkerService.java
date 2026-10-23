package com.repopilot.service;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.Range;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MethodChunkerService {

    private static final Logger logger = LoggerFactory.getLogger(MethodChunkerService.class);

    public MethodChunkerService() {
        StaticJavaParser.setConfiguration(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
    }

    public List<MethodChunk> chunk(String source, String filePath) {
        final CompilationUnit compilationUnit;
        try {
            compilationUnit = StaticJavaParser.parse(source);
        } catch (ParseProblemException exception) {
            logger.warn("Skipping Java source file {} because parsing failed: {}", filePath, exception.getMessage());
            return List.of();
        }

        List<String> lines = source.lines().toList();
        List<MethodDeclaration> methods = compilationUnit.findAll(MethodDeclaration.class);
        return methods.stream()
                .map(method -> toChunk(method, lines, filePath))
                .toList();
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
}
