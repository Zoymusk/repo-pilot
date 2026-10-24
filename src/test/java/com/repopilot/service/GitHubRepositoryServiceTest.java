package com.repopilot.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubRepositoryServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void removesTemporaryDirectoryContainingReadOnlyFiles() throws IOException {
        GitHubRepositoryService service = new GitHubRepositoryService(new MethodChunkerService());
        Path repositoryDirectory = Files.createDirectory(temporaryDirectory.resolve("repository"));
        Path readOnlyFile = Files.writeString(repositoryDirectory.resolve("read-only.txt"), "content");
        Files.setAttribute(readOnlyFile, "dos:readonly", true);

        service.deleteTemporaryDirectory(repositoryDirectory);

        assertFalse(Files.exists(repositoryDirectory));
    }

    @Test
    void retriesFailedCleanup() {
        int[] cleanupAttempts = {0};
        GitHubRepositoryService service = new GitHubRepositoryService(new MethodChunkerService()) {
            @Override
            void deleteDirectoryOnce(Path directory) throws IOException {
                cleanupAttempts[0]++;
                if (cleanupAttempts[0] < 3) {
                    throw new IOException("Temporary deletion failure");
                }
            }

            @Override
            boolean pauseBeforeCleanupRetry(Path directory) {
                return true;
            }
        };

        service.deleteTemporaryDirectory(temporaryDirectory);

        assertEquals(3, cleanupAttempts[0]);
    }

    @Test
    void continuesWhenCleanupStillFailsAfterRetries() {
        int[] cleanupAttempts = {0};
        GitHubRepositoryService service = new GitHubRepositoryService(new MethodChunkerService()) {
            @Override
            void deleteDirectoryOnce(Path directory) throws IOException {
                cleanupAttempts[0]++;
                throw new IOException("Temporary deletion failure");
            }

            @Override
            boolean pauseBeforeCleanupRetry(Path directory) {
                return true;
            }
        };

        assertDoesNotThrow(() -> service.deleteTemporaryDirectory(temporaryDirectory));
        assertEquals(3, cleanupAttempts[0]);
    }

    @Test
    void normalizesSeveralPrefixesAndMatchesLiteralPathPrefixes() {
        var prefixes = GitHubRepositoryService.normalizePathPrefixes(
                " src\\main\\java\\com\\Example, src/main/resources, src/main/java/com/Order"
        );

        assertEquals(
                java.util.List.of("src/main/java/com/Example", "src/main/resources", "src/main/java/com/Order"),
                prefixes
        );
        assertTrue(GitHubRepositoryService.isUnderPathPrefix(
                "src/main/java/com/Example.java",
                prefixes.get(0)
        ));
        assertTrue(GitHubRepositoryService.isUnderPathPrefix(
                "src/main/java/com/OrderService.java",
                "src/main/java/com/Order"
        ));
        assertFalse(GitHubRepositoryService.isUnderPathPrefix(
                "src/main/java/com/Examples.java",
                "src/main/java/com/Example/"
        ));
    }

    @Test
    void normalizesSinglePrefixAndMatchesLiteralPrefix() {
        String prefix = GitHubRepositoryService.normalizePathPrefix(" src\\main\\java\\ ");

        assertEquals("src/main/java", prefix);
        assertTrue(GitHubRepositoryService.isUnderPathPrefix("src/main/java/App.java", prefix));
        assertTrue(GitHubRepositoryService.isUnderPathPrefix("src/main/javascript/App.java", prefix));
        assertFalse(GitHubRepositoryService.isUnderPathPrefix("src/test/java/App.java", prefix));
        assertTrue(GitHubRepositoryService.isUnderPathPrefix("src/App.java", ""));
        assertTrue(GitHubRepositoryService.isUnderPathPrefix("src/main/java/App.java", "src/main/java/App"));
        assertEquals(java.util.List.of(), GitHubRepositoryService.normalizePathPrefixes(" , , "));
    }

    @Test
    void rejectsAbsoluteAndTraversalFolderPrefixes() {
        assertThrows(IllegalArgumentException.class, () -> GitHubRepositoryService.normalizePathPrefix("../src"));
        assertThrows(IllegalArgumentException.class, () -> GitHubRepositoryService.normalizePathPrefix("/src"));
        assertThrows(IllegalArgumentException.class, () -> GitHubRepositoryService.normalizePathPrefix("src/../test"));
        assertThrows(
                IllegalArgumentException.class,
                () -> GitHubRepositoryService.normalizePathPrefix("a".repeat(513))
        );
    }
}
