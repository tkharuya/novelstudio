# Generation journal checks

Run `bash tests/background/journal/run.sh /path/org-json.jar` from the project root. The harness compiles the real `GenerationJournal.java` against `org.json`, replacing only Android `Context` and `AtomicFile` with local JVM shims. Use JDK 17 or later on PATH, or set `JOURNAL_JAVA_HOME`. The JSON jar may also be supplied through `JOURNAL_JSON_JAR`. Compilation and execution use a 256 MiB Java heap.

The checks cover:

- Durable `begin` before a network request can be started; duplicate IDs and invalid inputs are rejected.
- Per-job sequence numbers, streamed text accumulation, compact delta events, defensive snapshots, and concurrent delivery serialization.
- A checkpoint one second after the first dirty delta; a failed asynchronous checkpoint is surfaced to the next event instead of silently accepting further deltas.
- Synchronous terminal persistence, partial-text retention on empty error payloads, and ignoring late cancellation or duplicate completion.
- Process restart changing previously active records to `interrupted`, retaining saved partial text and never resubmitting.
- Exact terminal-sequence acknowledgements, durable deletion, and restoring the pending in-memory record if acknowledgement persistence fails.
- Candidate-only manuscript/title/synopsis restoration; editable manuscript, title, review flag, and prior draft remain unchanged.
- Start-source preservation, a marked unavailable source when no start snapshot exists, and protection of a different candidate request.
- Terminal receipts preventing already applied or discarded results from being resurrected after a failed acknowledgement.
- Image metadata normalization, safe UUID references, duplicate-image prevention, and retaining results when the original chapter has 50 illustrations.
- Request-secret whitelisting, source/output bounds, 32-record limits, and reserving worst-case escaped JSON capacity before starting another request.
- Corrupt UTF-8 or duplicate disk records failing closed without overwriting the original, plus AtomicFile backup recovery.
- Injected write failures and silent rename failures: terminal results remain in memory for a later flush and late cancellation does not erase them.

The shim exercises real files, fsync, and rename behavior on this host. It is not an Android runtime test. Device process killing, Activity recreation, foreground-service behavior, OS notification controls, and real Android `AtomicFile` behavior still require device testing. The harness makes no network requests and consumes no API quota.

## Persistence limits

The journal stores at most 32 pending jobs and 32 MiB. A manuscript job reserves capacity for 2,097,152 UTF-16 units; a metadata job reserves 200,000. Escaped JSON can need six bytes per unit, so concurrent manuscript jobs can reach the reservation cap before 32 jobs. Unacknowledged terminal records are never evicted to make room.

Sources are retained only when explicitly supplied at start, or inherited from the matching saved candidate during merge. Raw request prompts, instructions, tokens, and API keys are not journal fields. A completed image's existing metadata may contain its saved illustration prompt (up to 32,000 units).

`runtimeReceipts` must be persisted with the applied candidate/image before calling `acknowledge(requestId, seq)`. A missing target, conflicting candidate, or full illustration list must not be acknowledged. `mergeInto` only returns JSON; it does not write the manuscript store or acknowledge journal entries.
