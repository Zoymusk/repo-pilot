package com.repopilot.service;

import com.repopilot.api.RepositoryScanController.RepositoryScanResponse;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.WindowCacheConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Arrays;
import java.util.stream.Stream;

@Service
public class GitHubRepositoryService {

    private static final Logger logger = LoggerFactory.getLogger(GitHubRepositoryService.class);
    private static final int CLEANUP_ATTEMPTS = 3;
    private static final long CLEANUP_RETRY_DELAY_MILLIS = 200;
    private static final int MAX_PATH_PREFIX_LENGTH = 512;

    private final MethodChunkerService methodChunkerService;

    public GitHubRepositoryService(MethodChunkerService methodChunkerService) {
        this.methodChunkerService = methodChunkerService;
        WindowCacheConfig windowCacheConfig = new WindowCacheConfig();
        // Memory-mapped pack files cannot be deleted during temporary clone cleanup on Windows.
        windowCacheConfig.setPackedGitMMAP(false);
        windowCacheConfig.install();
    }

    public RepositoryScanResponse scan(String repositoryUrl) throws IOException, GitAPIException {
        return withClonedRepository(repositoryUrl, repositoryDirectory -> {
            List<String> javaFiles = findJavaFiles(repositoryDirectory);
            return new RepositoryScanResponse(javaFiles, javaFiles.size());
        });
    }

    public List<MethodChunkerService.MethodChunk> chunks(String repositoryUrl) throws IOException, GitAPIException {
        return chunks(repositoryUrl, null);
    }

    public List<MethodChunkerService.MethodChunk> chunks(String repositoryUrl, String pathPrefix)
            throws IOException, GitAPIException {
        return chunks(repositoryUrl, pathPrefix, Integer.MAX_VALUE);
    }

    public List<MethodChunkerService.MethodChunk> chunks(
            String repositoryUrl,
            String pathPrefix,
            int maxChunks
    ) throws IOException, GitAPIException {
        List<String> normalizedPrefixes = normalizePathPrefixes(pathPrefix);
        try {
            return withClonedRepository(repositoryUrl, repositoryDirectory -> {
                List<String> scopedFiles = findJavaFiles(repositoryDirectory).stream()
                        .filter(filePath -> !filePath.startsWith("src/test/"))
                        .filter(filePath -> isUnderAnyPathPrefix(filePath, normalizedPrefixes))
                        .toList();
                logger.info(
                        "Found {} Java files in path prefix '{}' after cloning repository {}",
                        scopedFiles.size(),
                        normalizedPrefixes.isEmpty() ? "." : String.join(",", normalizedPrefixes),
                        repositoryUrl
                );

                List<MethodChunkerService.MethodChunk> chunks = new ArrayList<>();
                logger.info("Starting chunking of {} Java files for {}", scopedFiles.size(), repositoryUrl);
                for (String filePath : scopedFiles) {
                    String source = Files.readString(repositoryDirectory.resolve(filePath));
                    int remainingChunkLimit = maxChunks - chunks.size();
                    chunks.addAll(methodChunkerService.chunk(source, filePath, remainingChunkLimit));
                }
                logger.info("Chunked {} Java files into {} chunks for {}", scopedFiles.size(), chunks.size(), repositoryUrl);
                return List.copyOf(chunks);
            });
        } catch (IOException | GitAPIException | RuntimeException exception) {
            logger.error("Repository clone or chunking stage failed for {}", repositoryUrl, exception);
            throw exception;
        }
    }

    static String normalizePathPrefix(String pathPrefix) {
        if (pathPrefix == null || pathPrefix.isBlank()) {
            return "";
        }
        String normalizedPrefix = pathPrefix.trim().replace('\\', '/');
        if (normalizedPrefix.length() > MAX_PATH_PREFIX_LENGTH) {
            throw new IllegalArgumentException(
                    "Path prefix must be no longer than " + MAX_PATH_PREFIX_LENGTH + " characters"
            );
        }
        normalizedPrefix = normalizedPrefix.replaceAll("/+$", "");
        if (normalizedPrefix.isBlank()
                || normalizedPrefix.startsWith("/")
                || normalizedPrefix.matches("^[A-Za-z]:.*")
                || Stream.of(normalizedPrefix.split("/", -1))
                        .anyMatch(segment -> segment.isBlank() || segment.equals(".") || segment.equals(".."))) {
            throw new IllegalArgumentException("Path prefix must be a repository-relative folder path");
        }
        return normalizedPrefix;
    }

    static List<String> normalizePathPrefixes(String pathPrefixes) {
        if (pathPrefixes == null || pathPrefixes.isBlank()) {
            return List.of();
        }
        if (pathPrefixes.length() > MAX_PATH_PREFIX_LENGTH) {
            throw new IllegalArgumentException(
                    "Path prefixes must be no longer than " + MAX_PATH_PREFIX_LENGTH + " characters"
            );
        }
        return Arrays.stream(pathPrefixes.split(",", -1))
                .map(String::trim)
                .filter(prefix -> !prefix.isBlank())
                .map(GitHubRepositoryService::normalizePathPrefix)
                .distinct()
                .toList();
    }

    static boolean isUnderPathPrefix(String filePath, String pathPrefix) {
        return pathPrefix.isEmpty() || filePath.startsWith(pathPrefix);
    }

    private boolean isUnderAnyPathPrefix(String filePath, List<String> pathPrefixes) {
        return pathPrefixes.isEmpty() || pathPrefixes.stream().anyMatch(prefix -> isUnderPathPrefix(filePath, prefix));
    }

    private <T> T withClonedRepository(String repositoryUrl, RepositoryOperation<T> operation)
            throws IOException, GitAPIException {
        URI repositoryUri = validateGitHubRepositoryUrl(repositoryUrl);
        Path temporaryDirectory = Files.createTempDirectory("repo-pilot-");

        try {
            try (Git git = Git.cloneRepository()
                    .setURI(repositoryUri.toString())
                    .setDirectory(temporaryDirectory.toFile())
                    .setDepth(1)
                    .setTimeout(30)
                    .call();
                 Repository repository = git.getRepository()) {
                logger.info("Clone completed for {}", repositoryUri);
                return operation.apply(temporaryDirectory);
            }
        } finally {
            deleteTemporaryDirectory(temporaryDirectory);
        }
    }

    @FunctionalInterface
    private interface RepositoryOperation<T> {
        T apply(Path repositoryDirectory) throws IOException;
    }

    void deleteTemporaryDirectory(Path directory) {
        for (int attempt = 1; attempt <= CLEANUP_ATTEMPTS; attempt++) {
            try {
                deleteDirectoryOnce(directory);
                return;
            } catch (IOException exception) {
                if (attempt == CLEANUP_ATTEMPTS) {
                    logger.warn(
                            "Unable to remove temporary repository directory {} after {} attempts",
                            directory,
                            CLEANUP_ATTEMPTS,
                            exception
                    );
                    return;
                }
                logger.debug(
                        "Unable to remove temporary repository directory {} on attempt {}/{}; retrying",
                        directory,
                        attempt,
                        CLEANUP_ATTEMPTS,
                        exception
                );
                if (!pauseBeforeCleanupRetry(directory)) {
                    return;
                }
            }
        }
    }

    void deleteDirectoryOnce(Path directory) throws IOException {
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                makeWritable(file);
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                makeWritable(directory);
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    boolean pauseBeforeCleanupRetry(Path directory) {
        try {
            Thread.sleep(CLEANUP_RETRY_DELAY_MILLIS);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while retrying cleanup of temporary repository directory {}", directory);
            return false;
        }
    }

    private void makeWritable(Path path) throws IOException {
        DosFileAttributeView attributes = Files.getFileAttributeView(path, DosFileAttributeView.class);
        if (attributes != null && attributes.readAttributes().isReadOnly()) {
            attributes.setReadOnly(false);
        }
    }

    private URI validateGitHubRepositoryUrl(String repositoryUrl) {
        final URI uri;
        try {
            uri = URI.create(repositoryUrl.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Provide a valid HTTPS GitHub repository URL", exception);
        }

        String path = uri.getPath();
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !"github.com".equalsIgnoreCase(uri.getHost())
                || uri.getUserInfo() != null
                || uri.getPort() != -1
                || uri.getQuery() != null
                || uri.getFragment() != null
                || path == null
                || path.contains("%")) {
            throw new IllegalArgumentException("Only HTTPS GitHub repository URLs are supported");
        }

        String[] segments = path.replaceAll("^/|/$", "").split("/", -1);
        if (segments.length != 2
                || !segments[0].matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})")
                || !segments[1].matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("Provide a GitHub URL in the form https://github.com/owner/repository");
        }

        if (segments[1].endsWith(".git")) {
            segments[1] = segments[1].substring(0, segments[1].length() - 4);
        }
        if (segments[1].isBlank() || segments[1].equals(".") || segments[1].equals("..")) {
            throw new IllegalArgumentException("Provide a valid GitHub repository name");
        }

        return URI.create("https://github.com/" + segments[0] + "/" + segments[1] + ".git");
    }

    private List<String> findJavaFiles(Path repositoryDirectory) throws IOException {
        try (Stream<Path> paths = Files.walk(repositoryDirectory)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> !path.startsWith(repositoryDirectory.resolve(".git")))
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .map(repositoryDirectory::relativize)
                    .map(path -> path.toString())
                    .map(path -> path.replace('\\', '/'))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        }
    }
}
