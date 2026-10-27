# Repo Pilot

Repo Pilot helps developers understand an unfamiliar codebase without spending hours searching through files.

When you join an open-source project for the first time, it is hard to know the folder structure, which classes matter, and where a feature is implemented. With Repo Pilot you give it a public GitHub repository and ask a question in plain English. It answers from the indexed files and lists the source files and line numbers it used, so you can verify the answer instead of trusting it blindly. It also suggests a plan for a pull request.

## How it works

1. **Index.** It clones a public GitHub repo, reads the Java files, and splits them into method-level chunks. A folder filter lets you index only the part you care about.
2. **Embed.** Each chunk is embedded with the Gemini API. Embeddings are cached by a hash of the text, so the same code is not processed twice.
3. **Retrieve.** Your question is embedded, and the closest chunks are retrieved.
4. **Answer.** A Gemini model writes the answer from those chunks only, with citations and a PR plan. Citations that don't match a retrieved file are rejected.

Indexing logs its progress batch by batch, so you can see where something goes wrong.

## Run it

Requires Java 21 and Maven. Set your own Gemini key (never commit it):

```powershell
$env:GEMINI_API_KEY="your-key"
$env:GEMINI_MODEL="gemini-3.8-flash"
$env:GEMINI_FALLBACK_MODEL="gemini-3.7-flash"
$env:GEMINI_EMBEDDING_MODEL="gemini-embedding-2"
mvn spring-boot:run
```

Open http://localhost:8080, enter a repository URL (and optionally a folder), click **Index repository**, then ask a question.

## A real result

I used Repo Pilot on [CodeMonk](https://github.com/YeamimHossainSajid/CodeMonk) for issue #357. It pointed me to the existing Kafka event tests, but several of the gaps it suggested were wrong (for example, `hashCode` was already tested). I checked each suggestion against the repository before writing anything. The result is [PR #497](https://github.com/YeamimHossainSajid/CodeMonk/pull/497): 12 new tests, CI passing.

## Limits

- Java files only.
- The index lives in memory and is lost when the app restarts.
- The free Gemini tier has daily and per-minute quotas, and the models sometimes return 503 under load. The app retries and falls back to a second model.
- Answers can be wrong. That is why every answer shows its sources.

## What I learned building it

Model errors came from different places: a retired model name, a daily quota, a busy server, a dropped connection, and a parser language level. I read each error, tested one thing at a time, and made one focused change.
