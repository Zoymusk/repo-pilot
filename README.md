# Repo Pilot

A Java 21 Spring Boot API that clones a public GitHub repository into a temporary
folder, lists its Java files, and returns the list and count. The temporary clone
is removed after the scan. It can be built as a Docker image and deployed as a
Render web service.

The repository index uses Gemini embeddings through its REST API and keeps
vectors in memory. Set `GEMINI_API_KEY` in the environment before indexing or
searching. The index is lost when the application restarts.

## Run

Start the application without a database:

```powershell
mvn spring-boot:run
```

Set the Gemini API key in PowerShell before starting the app:

```powershell
$env:GEMINI_API_KEY = "your-api-key"
$env:GEMINI_MODEL = "your-main-model-name"
$env:GEMINI_FALLBACK_MODEL = "your-fallback-model-name"
```

The Gemini key and answer model names are read from environment variables only;
do not put their values in project files. `GEMINI_FALLBACK_MODEL` is optional.

## Scan a repository

```powershell
Invoke-RestMethod -Method Post `
  -Uri http://localhost:8080/api/repositories/scan `
  -ContentType "application/json" `
  -Body '{"repositoryUrl":"https://github.com/spring-projects/spring-petclinic"}'
```

The response contains repository-relative paths (using `/` separators) and the
number of Java files:

```json
{
  "javaFiles": ["src/main/java/example/Application.java"],
  "count": 1
}
```

Only HTTPS URLs for repositories hosted directly under `github.com` are
accepted.

## Chunk methods

The chunks endpoint clones the repository temporarily and returns one chunk for
each Java method. The indexing endpoint skips files under `src/test/`, embeds
method chunks in batches, and replaces the current in-memory index.

```powershell
Invoke-RestMethod -Method Post `
  -Uri http://localhost:8080/api/repositories/index `
  -ContentType "application/json" `
  -Body '{"repositoryUrl":"https://github.com/spring-projects/spring-petclinic"}'
```

The index response contains `indexedChunkCount`. Search the in-memory index with
a natural-language question:

```json
{
  "indexedChunkCount": 1
}
```

```powershell
Invoke-RestMethod -Method Post `
  -Uri http://localhost:8080/api/repositories/search `
  -ContentType "application/json" `
  -Body '{"question":"Where does the application start?"}'
```

Search returns up to five chunks, ordered by cosine similarity, with their
source paths, line ranges, contents, and scores.

## Ask about the indexed repository

The ask endpoint retrieves the top five indexed chunks and asks Gemini for a
JSON explanation, source citations, and a pull-request plan. Citations and
planned source files are validated against retrieved chunks. Configure the main
answer model using the required `GEMINI_MODEL` environment variable. Requests
that receive HTTP 429 or 503 are retried up to five times with exponential
backoff and jitter. If those attempts fail, the service tries the model
configured by the optional `GEMINI_FALLBACK_MODEL` environment variable once.
If both models are unavailable, the ask endpoint returns HTTP 503 and asks the
user to try again in a minute.

```powershell
Invoke-RestMethod -Method Post `
  -Uri http://localhost:8080/api/repositories/ask `
  -ContentType "application/json" `
  -Body '{"question":"How is application startup configured?","issueDescription":"Startup should handle missing configuration."}'
```

The issue description is optional. Index the repository before asking a question.

## Deploy on Render with Docker

Create a Render Web Service from this repository and select **Docker** as the
runtime. Render builds the image from the included multi-stage `Dockerfile`.
Set `GEMINI_API_KEY`, `GEMINI_MODEL`, and optionally `GEMINI_FALLBACK_MODEL` in
the service's Environment settings; keep their values out of the repository.
The server listens on Render's `PORT` environment variable (defaulting to
`8080`). Use `/health` as the health check path; it returns `OK`.
