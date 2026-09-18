# AGENTS.md — Kotlin RAG Service

One codex agent owns the whole repo (`src/`, `test/`, `pom.xml`). Shared docs in
`docs/`. A nested `AGENTS.md` refines (never cancels) these rules.

## Phase records — MANDATORY every task
Phase records are the single source of truth. Keeping them current is part of
every task. Only an explicit user instruction (e.g. "don't touch the phase docs")
suspends this; silence does not.

| Area | Current phase | Backlog |
| --- | --- | --- |
| Service | `docs/work_current_phase.md` | `docs/next_phase.md` |

**Intake:** never start work that has no row in `work_current_phase.md`. Move the
item from `next_phase.md` into `work_current_phase.md` first.

**Before a task:** read both files; set the task row to **In progress** with scope
+ any approval dependency; update **Last reviewed**.

**After a task, before reporting back:**
1. Set row to **Completed / Blocked / Deferred** with evidence: commands + results,
   files touched, commit hash, and what was *not* run.
2. Update **Last reviewed**.
3. Add new follow-ups/scope changes to `next_phase.md`.
4. On phase close, record it in `docs/complete_phases.md`.

Never end a code/doc-changing response without the matching phase-record update.
Close every response with `Phase records: <file(s) updated>` or
`Phase records: unchanged — <reason>`.

## Conventions
- Prefix commit messages with the task ID (`SVC-06: …`) so evidence is greppable.
- If unsure of today's date, ask; never guess a `Last reviewed` stamp.
- Format with `mvn spotless:apply` before committing.

## Stack
- Language/runtime: Kotlin 2.3.21
- Framework: Spring Boot 4.1.x
- AI: Spring AI 2.0.1, Embabel 1.5.2
- Target jdk 25
- Build tool: Maven
- Testing: JUnit 5 + Mockito


## Commands
- Build: `mvn clean package`
- Test: `mvn test`
- Run: `mvn spring-boot:run`
- Format check: `mvn spotless:check`

## Architecture
- Keep controller / service / repository layers separate; no logic in controllers.
- DB schema comes from versioned migrations (Flyway), never at runtime.
- Config via `application.yml` + profiles; no hard-coded secrets.
- Always prefer @Configuration files for bean definitions, but component-scan should be used for single impl (non-mocked) services

## Delivery
- Add/adjust tests for new behavior.
- Update docs and `application-example.yml` on any config change.


## Safety
- Don't start/stop/configure the app server, database, Docker, or IDEs unless
  explicitly asked. Announce first.
- Don't edit `.env`, `application*.yml` secrets, credentials, or keystores unless asked.
- Don't run tests hitting live services without prior approval.
- Run the smallest relevant check when permitted; always state what was not run.

## Precedence
Explicit user prompt > this file (+ nested `AGENTS.md`) > phase records > other docs.

