package com.repopilot.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
}
