# Repo Pilot

**Ask questions about an unfamiliar codebase and get answers you can check.**

Repo Pilot helps developers who are new to a project. Give it a public GitHub repository, ask a question in plain English, and it answers from the real code. Every answer lists the files and line numbers it used, plus a suggested plan for a pull request.

## Why I built it

Joining an open-source project for the first time is slow. You don't know the folder structure, which classes matter, or where a feature lives. Repo Pilot gets you to the right files in minutes, and the citations let you verify the answer yourself.

## What it does

- Reads a public GitHub repo and splits Java code into method-level chunks
- Lets you index just one folder of a large repo
- Finds the chunks closest to your question using Gemini embeddings
- Writes an answer with file and line citations and a pull request plan
- Caches embeddings so the same code is never processed twice
- Shows indexing progress, and retries or switches to a backup model when the AI service is busy

## See it in action

I used Repo Pilot on [CodeMonk](https://github.com/YeamimHossainSajid/CodeMonk) for issue #357. It pointed me to the right Kafka event test files. I used the citations to check each idea against the code, and wrote tests only for the gaps I confirmed.

The result is [PR #497](https://github.com/YeamimHossainSajid/CodeMonk/pull/497): 12 new tests, CI passing.

## Built with Wispr Flow

I built Repo Pilot for the No Hands Challenge (Builders track), using voice as my main input.

- My first Flow was the project statement itself: the problem I wanted to fix and what the tool should do.
- I hold Caps Lock to talk. I use it to describe features and fixes to Copilot in VS Code, and to write notes, docs, and descriptions.
- The app is built for voice too: you ask your question out loud, and the answer comes back with sources.
- I still use the keyboard for short edits and for checking commands, file names, and settings, because a sentence can sound right and contain a wrong detail.

What I liked most: Flow understood me well, and it knew when to start a new paragraph, so my long explanations came out ready to use without much editing.
What worked: Fast, natural dictation that helped me write prompts and express ideas with less effort.
What got in the way: Occasional transcription errors and the need to correct technical terms.
What I’d want next: Better technical accuracy, more consistent transcription, and smoother integration with coding tools.

## Run it

You need Java 21 and Maven. Set your own Gemini key (never commit it):

```powershell
$env:GEMINI_API_KEY="your-key"
$env:GEMINI_MODEL="gemini-3.8-flash"
$env:GEMINI_FALLBACK_MODEL="gemini-3.7-flash"
$env:GEMINI_EMBEDDING_MODEL="gemini-embedding-2"
mvn spring-boot:run
```

Open http://localhost:8080, enter a repository URL and an optional folder, click **Index repository**, then ask your question.

## What's next

- Save the index to disk so it survives a restart
- Support languages beyond Java
- Handle larger repositories with a paid Gemini plan

## What I learned

Debugging is a process. When errors appeared (a retired model, a quota, a busy server, a dropped connection), I read each message, tested one thing at a time, and made one focused change.
