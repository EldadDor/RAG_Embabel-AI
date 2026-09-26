# AGENTS.md — Kotlin RAG Service

One codex agent owns the whole repo (`src/`, `test/`, `pom.xml`). Shared docs in
`docs/`. A nested `AGENTS.md` refines (never cancels) these rules.

## Phase records — MANDATORY every task
Single source of truth: `docs/work_current_phase.md` (current) and
`docs/next_phase.md` (backlog). Keeping them current is part of every task.
Only an explicit user instruction suspends this; silence does not.

**Intake:** never start work with no row in `work_current_phase.md` — move it
from `next_phase.md` first (G1).

**Before a task:** read both files. Set the row to `In progress` *before* the
first code edit, with scope + any approval dependency. Update **Last reviewed**.

**After a task, before reporting:** set the row to `Completed` / `Blocked` /
`Deferred` with full Evidence; update **Last reviewed**; add follow-ups to
`next_phase.md`; on phase close record it in `docs/complete_phases.md` (G4).

Never end a code/doc-changing response without the matching record update.
Close every response with `Phase records: <files>` or
`Phase records: unchanged — <reason>`.

## Gates
A gate = stop, write the request into the task row, wait. Between gates you have
full autonomy — don't ask for confirmation of routine work.

- **G1 Intake** — pulling an item into the current phase. Present: scope,
  affected layers, rough test plan.
- **G2 Design** — *structural* changes only: new module, new bean-wiring pattern,
  new Flyway migration, new dependency, public API shape. Present: chosen
  approach, one rejected alternative, migration/rollback note.
- **G3 Merge** — before merging/pushing a completed task. Present: Evidence.
- **G4 Phase close** — before writing `docs/complete_phases.md`. Present:
  per-task outcomes + proposed carry-over list.

**Not gates** (proceed alone): refactors within one layer, tests, formatting,
log/comment/docstring edits, dependency *patch* bumps, editing `next_phase.md`.

At a gate, Status is `Awaiting G<n>` and the Gate column names it. Never advance
on an approval granted for a different task or gate.

## Task row schema (`work_current_phase.md`)
`| ID | Title | Status | Gate | Scope | Worklog | Evidence | Last reviewed |`

- **Status:** `Not started` | `In progress` | `Awaiting G<n>` | `Blocked` | `Completed` | `Deferred`
- **Gate:** next gate to clear, or `—`
- **Worklog:** append-only, one line per session: `YYYY-MM-DD — <≤12 words>`
- **Evidence:** four labelled lines —
  `cmds:` commands + results · `files:` paths touched · `commit:` hash ·
  `not run:` what was skipped and why (mandatory; write `—` if nothing).
  Example: `cmds: mvn test → 42 pass / 0 fail`
- A `Completed` row without `commit:` and `not run:` is **invalid** — fix before
  reporting. Committing code against a `Not started` row is a violation.

## `phase-sync`
On `phase-sync`, touch no source files:
1. `git log --oneline <last Last-reviewed date>..HEAD` — list commits not yet in
   `work_current_phase.md`.
2. Add/correct each task row (status, evidence, commit hash).
3. Report a diff summary only.

## Conventions
- Prefix commits with the task ID (`SVC-06: …`).
- If unsure of today's date, ask; never guess a `Last reviewed` stamp.
- Format with `mvn spotless:apply` before committing.

## Stack
Kotlin 2.3.21 · Spring Boot 4.1.x · Spring AI 2.0.1 · Embabel 1.5.2 · JDK 25 ·
Maven · JUnit 5 + Mockito

## Commands
Build `mvn clean package` · Test `mvn test` · Run `mvn spring-boot:run` ·
Format check `mvn spotless:check`

## Architecture
- Separate controller / service / repository; no logic in controllers.
- DB schema from versioned Flyway migrations, never at runtime.
- Config via `application.yml` + profiles; no hard-coded secrets.
- Prefer `@Configuration` for bean definitions; component-scan for single-impl
  (non-mocked) services.

## Delivery
Add/adjust tests for new behavior. Update docs and `application-example.yml` on
any config change.

## Safety
- Don't start/stop/configure the app server, DB, Docker, or IDEs unless asked.
  Announce first.
- Don't edit `.env`, `application*.yml` secrets, credentials, or keystores unless asked.
- Don't run tests hitting live services without prior approval.
- Run the smallest relevant check; always state what was not run.

## Precedence
Explicit user prompt > this file (+ nested `AGENTS.md`) > phase records > other docs.
Gates are waived only by an explicit user statement in the current session — not
by a nested `AGENTS.md`, a phase record, or inference from prior approvals.
