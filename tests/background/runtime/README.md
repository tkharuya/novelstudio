# GenerationRuntime offline orchestration checks

This suite compiles the production `GenerationRuntime.java` against JVM-only boundary fakes. It checks the order and ownership of service readiness, inference, cancellation, persistence and UI delivery without making network or paid API requests.

## Run

From the project root:

```bash
bash tests/background/runtime/run.sh /absolute/path/to/org-json.jar
```

To replace the checked-in result transcript:

```bash
bash tests/background/runtime/run.sh /absolute/path/to/org-json.jar > tests/background/runtime/RESULTS.txt
```

Requirements are Bash, `rg`, a JDK that supports `java -m jdk.compiler/com.sun.tools.javac.Main`, and a standalone `org.json` runtime JAR. Compilation and execution each use a 256 MiB heap limit. The script creates temporary class files and removes them on exit. None of the harness classes belong in the APK.

## Covered behavior

- Neither native inference client runs before foreground-service readiness, or after a foreground-service start failure.
- An Activity must be foregrounded to request new work. Pausing, detaching or replacing it does not cancel already running text or image work.
- Terminal journal persistence completes before the corresponding UI completion and removal of active work. Background results remain available for snapshot/replay.
- Cancellation requested before or during journal preparation prevents inference. A queued cancellation targets the captured request and cannot cancel a later request.
- Normal service idle-stop and timeout invalidate readiness before replacement work can dispatch. Stale destroy/timeout callbacks cannot revoke a newer service's readiness or cancel its work.
- Timeout races cover both a completed old request whose UI callback starts a replacement, and an idle service expiring concurrently with a new image request. A new readiness notification can come from a replacement Service or the same surviving Service instance.
- Repeated starts and terminal events do not duplicate inference, persistence or UI completion; late events cannot clear a newer request.
- Invalid requests and unavailable journal storage prevent inference. Terminal disk-write failure remains an error and exposes the journal's retained RAM result through replay.
- Notification permission/channel state is reported separately from foreground-service start success.
- ACK cannot remove a terminal journal record without a saved manuscript receipt marked terminal and covering the requested sequence. Missing, nonterminal and stale receipts are rejected.

The exact current scenario and assertion counts are recorded in `RESULTS.txt`.

## Scope and limitations

The fake journal models its public contract, including retaining the latest in-memory result after a disk-write failure. Its begin/record gates and the runtime control-executor gate allow deterministic race orderings without sleeps. Separate journal tests cover real file persistence and manuscript merging.

`GenerationService` in this harness supplies constants only. These checks do **not** run Android's service framework or verify actual `startForeground`, `stopSelfResult`, `onTimeout`, wake-lock release, notification delivery, screen-off behavior, Doze, process termination or manufacturer battery restrictions. They also do not exercise OAuth, HTTP, API credentials, billing or image decoding. Those boundaries require the corresponding client tests, production Android compilation and device checks.
