# Atlas: Durable Agent Runtime

Status: architecture baseline for the next implementation phase  
Target: Android + Windows, with no mandatory cloud inference dependency

## Goal

Atlas should be able to accept a task, keep working while its window is hidden, survive provider failures and application restarts, and report progress without requiring the user to keep a chat screen open.

"Always on" means the runtime is available whenever at least one opted-in device is powered, connected as needed, and allowed to run. It is not a promise of execution while every device is off, offline, or suspended.

## Existing foundation (verified in the current code)

### Android
- Providers.kt supports Gemini, OpenAI-compatible providers, Anthropic, OpenRouter, Groq, DeepSeek, xAI and custom endpoints.
- Provider keys are pooled locally. Exhausted keys are rested; other keys and providers can take over.
- Conversation.kt executes tool loops and switches providers during a run.
- ModelService.kt isolates the on-device GGUF runtime in a separate process.
- Store.kt persists tasks, memories and the Log atomically.
- Sync.kt encrypts records on-device before syncing them with the Supabase RPC endpoint.

### Windows
- desktop/renderer/brain.js has a similar provider/key pool, provider failover and a local OpenAI-compatible model option (Ollama / LM Studio).
- desktop/main.js keeps the Electron app in the system tray when the window is closed and supports starting with Windows.
- The desktop app can continue reminders and synchronization while hidden.
- The desktop and Android apps share encrypted task, memory, Log and completed-chat records.

## The actual gap

The current agent loop is initiated by a chat message. Its provider/key failover is already present, but an in-progress run is not a durable job:

- A process restart can interrupt a run.
- Tool progress is primarily held in the live conversation object.
- Chat synchronization intentionally excludes the unfinished assistant message.
- There is no general persistent queue for scheduled or background agent jobs.
- There is no cross-device lease/claim protocol preventing both devices from executing the same job.

Do not rebuild provider rotation or the reminder engine as if they do not exist. Extend them.

## Target architecture

1. **Job journal (device-local, durable):** stores job ID, user request, creation/update times, status, execution owner, provider/model used, progress summaries, completed tool results, and a pending-tool marker. Never stores API keys.
2. **Worker:** claims one eligible job, executes bounded steps, checkpoints before and after every tool, and yields when it needs approval or a retry window.
3. **Recovery:** after restart, safely resumes jobs with a complete checkpoint. If a tool was marked pending when the process died, mark the job needs_review; do not blindly replay a possibly non-idempotent action.
4. **Provider router:** reuse existing provider/key pools. Prefer a suitable ready provider, observe rate limits and provider terms, use local inference where appropriate, and preserve task state when switching models.
5. **Approval gate:** sending messages, purchases, deleting data, account/security changes, and other consequential external actions require explicit approval unless the user already authorized that exact action.
6. **Cross-device coordination:** sync encrypted job metadata and completed checkpoints, but use a server-side atomic lease/claim with expiry so phone and PC cannot both own the same job. API keys remain device-local.
7. **Notifications and control:** show progress, pause/resume, cancel, and ask for approval without requiring the chat window to stay open.

## Job states

- queued: accepted, not yet claimed
- running: currently owned by one device
- waiting_retry: provider/network cooldown; retry at retry_at
- awaiting_approval: a consequential action needs the user's decision
- paused: user paused the job
- needs_review: process ended while a tool action's outcome was uncertain
- completed: finished successfully
- failed: cannot continue without a changed request or configuration
- cancelled: user cancelled it

Every transition is timestamped. A worker must verify ownership before writing progress or executing another step.

## Implementation sequence

### Phase 1 — Durable local execution (Windows first)
- Add a tested job-journal module using atomic writes.
- Persist the job before the first model call.
- Persist a tool intent before execution and the tool result immediately after.
- Recover safe interrupted jobs on app startup; put uncertain tool outcomes into needs_review.
- Keep the current chat interface as the way to create jobs; add visible resume/pause/cancel status.

Windows is the first worker because the current Electron app already remains alive in the tray and supports login startup. This does not remove the Android app's ability to work independently.

### Phase 2 — Background scheduling and notifications
- Add scheduled/recurring agent jobs separately from existing reminders.
- Use a bounded worker with retry/backoff, concurrency limits and cancellation.
- Surface approval requests and progress through desktop notifications and the app.

### Phase 3 — Android worker
- Port the same job-state rules to Kotlin.
- Use Android's supported persistent-work mechanisms for deferrable work and a user-visible foreground service only for active work that genuinely needs it.
- Respect battery restrictions and make the runtime's availability visible.

### Phase 4 — Cross-device job ownership
- Extend the existing encrypted sync protocol with job records and an atomic claim/lease RPC.
- Resolve stale leases after device failure.
- Keep provider secrets out of sync payloads and backups.

### Phase 5 — Optional cloud worker
- Only add a cloud execution host if the user chooses the ongoing hosting cost and trust boundary.
- Keep the cloud worker optional; Atlas identity, memory format, tools and job journal must remain provider/host independent.

## Reliability and security invariants

- Never claim unlimited usage. Rotation distributes requests across separately authorized quotas; it does not create quota or justify evading provider limits.
- Never sync API keys or other provider secrets.
- Never replay an uncertain external side effect automatically.
- Every job can be inspected, paused and cancelled by the user.
- Tool permissions are checked at execution time, not only when the model proposes an action.
- Provider switching must preserve completed work and must not repeat completed side effects.
- A failed model call must not corrupt the job journal.
- Local-only work should remain possible when the network is unavailable, within the capabilities of the installed local model and tools.
