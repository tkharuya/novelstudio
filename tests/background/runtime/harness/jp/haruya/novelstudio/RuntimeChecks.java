package jp.haruya.novelstudio;

import android.app.Activity;
import android.app.NotificationChannel;
import android.content.Context;
import android.content.Intent;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Offline orchestration checks; fake service/client/journal boundaries, no Android framework. */
public final class RuntimeChecks {
    private static int assertions, scenarios, failures;

    public static void main(String[] args) throws Exception {
        scenario("service readiness gates both API clients", RuntimeChecks::readyGate);
        scenario("foreground-service start failures send no API requests", RuntimeChecks::startFailures);
        scenario("foreground restrictions reject new work only", RuntimeChecks::foregroundRestriction);
        scenario("Activity pause/detach preserves native work and background results", RuntimeChecks::screenLifecycle);
        scenario("terminal persistence precedes UI completion", RuntimeChecks::terminalPersistence);
        scenario("cancel before preparation prevents API dispatch", RuntimeChecks::cancelBeforePreparation);
        scenario("cancel during preparation prevents API dispatch", RuntimeChecks::cancelDuringPreparation);
        scenario("queued cancellation cannot cancel replacement work", RuntimeChecks::cancelReplacementRace);
        scenario("service idle-stop handshake protects new work", RuntimeChecks::idleStopRaces);
        scenario("expired service cannot dispatch or cancel replacement work", RuntimeChecks::serviceExpiryRace);
        scenario("terminal events and active starts are not duplicated", RuntimeChecks::duplicateEvents);
        scenario("invalid requests never start a foreground service", RuntimeChecks::invalidRequests);
        scenario("metadata generation uses the same service gate", RuntimeChecks::metadataRequest);
        scenario("journal preparation failure prevents inference", RuntimeChecks::journalPreparationFailure);
        scenario("terminal storage failure retains recoverable RAM state", RuntimeChecks::terminalStorageFailure);
        scenario("service loss cancels only owned work", RuntimeChecks::serviceLoss);
        scenario("notifications and acknowledgements are bounded metadata", RuntimeChecks::notificationAndAcknowledgement);
        System.out.println((failures == 0 ? "PASS " : "FAIL ") + assertions + " assertions across " + scenarios
                + " runtime scenarios; " + failures + " failed scenarios.");
        System.out.println("LIMITS: JVM fakes for Android Context/Activity, Service, clients and journal; no paid API calls, real HTTP, Android foreground-service lifecycle, notification delivery, lock-screen, Doze, process-kill or disk-persistence verification.");
        if (failures != 0) throw new AssertionError("Runtime orchestration checks failed: " + failures);
    }

    private static void readyGate() throws Exception {
        try (Harness h = new Harness()) {
            h.runtime.startText(text("text_gate"));
            h.runtime.startImage(image("image_gate"));
            h.drain();
            check(h.text.calls.isEmpty() && h.image.calls.isEmpty(), "no inference before serviceReady");
            check(h.journal.begins == 2 && h.runtime.hasWork(), "both requests are journaled while awaiting service");
            check(h.context.starts.size() == 2, "foreground service requested for each explicit start");
            for (Intent intent : h.context.starts) {
                check(intent.target == GenerationService.class && GenerationService.ACTION_START.equals(intent.getAction()), "service request targets the fixed generation service");
                check(intent.extras.isEmpty(), "prompt and manuscript never cross the service Intent");
            }
            h.ready(); h.drain();
            check(h.text.calls.size() == 1 && h.image.calls.size() == 1, "serviceReady dispatches one call per job");
            h.runtime.serviceReady(h.service);
            h.runtime.startText(text("text_gate")); h.runtime.startImage(image("image_gate"));
            h.drain();
            check(h.text.calls.size() == 1 && h.image.calls.size() == 1 && h.journal.begins == 2, "duplicate ready/start do not repeat API or journal begin");
            check(h.context.starts.size() == 2, "duplicate active requests do not restart the service");
        }
    }

    private static void startFailures() throws Exception {
        for (boolean image : new boolean[]{false, true}) {
            try (Harness h = new Harness()) {
                h.context.startFailure = new IllegalStateException("CANARY_FGS_START_RESTRICTED");
                h.start(image, image ? image("fgs_image") : text("fgs_text")); h.drain();
                check(h.text.calls.isEmpty() && h.image.calls.isEmpty() && h.journal.begins == 0, "startForegroundService throw prevents journal/API startup");
                check(!h.runtime.hasWork(), "failed FGS start releases pending work");
                Event event = h.ui.last(image ? "illustration" : "generation");
                check("error".equals(event.data.optString("phase")) && !event.data.toString().contains("CANARY"), "FGS failure reports fixed safe text");
                h.runtime.serviceReady(h.service); h.drain();
                check(h.text.calls.isEmpty() && h.image.calls.isEmpty(), "late service readiness cannot revive rejected work");
            }
            try (Harness h = new Harness()) {
                h.start(image, image ? image("notify_image") : text("notify_text"));
                h.runtime.serviceStartFailed(); h.drain();
                check(h.text.calls.isEmpty() && h.image.calls.isEmpty(), "notification/service readiness failure sends no inference");
                check(!h.runtime.hasWork() && h.journal.terminalRecords == 1, "unstarted service failure is durably terminalized");
            }
        }
    }

    private static void foregroundRestriction() throws Exception {
        try (Harness h = new Harness()) {
            h.runtime.pauseUi(h.owner);
            h.runtime.startText(text("paused_text")); h.runtime.startImage(image("paused_image")); h.drain();
            check(h.context.starts.isEmpty() && h.text.calls.isEmpty() && h.image.calls.isEmpty(), "paused UI cannot request a new foreground service or inference");
            check(!h.runtime.hasWork() && h.journal.begins == 0, "background starts create no active work or journal entries");
            h.runtime.detachUi(h.owner);
            h.runtime.startText(text("detached_text")); h.drain();
            check(h.context.starts.isEmpty(), "detached UI cannot start work");
            Activity replacement = new Activity(h.context); ListenerLog replacementUi = new ListenerLog();
            h.runtime.attachUi(replacement, replacementUi);
            h.runtime.pauseUi(h.owner); h.runtime.detachUi(h.owner);
            check(h.runtime.ownsUi(replacement) && h.runtime.canDeliverTo(replacement), "stale lifecycle calls cannot background a newer screen");
            h.runtime.startText(text("resumed_text")); h.ready(); h.drain();
            check(h.text.calls.size() == 1, "resumed replacement can start work");
        }
    }

    private static void screenLifecycle() throws Exception {
        try (Harness h = new Harness()) {
            h.ready(); h.runtime.startText(text("background_text")); h.runtime.startImage(image("background_image")); h.drain();
            h.runtime.pauseUi(h.owner); h.runtime.detachUi(h.owner); h.drain();
            check(h.runtime.hasWork() && h.text.cancelled.isEmpty() && h.image.cancelled.isEmpty(), "pause/destroy detach never cancels active clients");
            check(h.text.closes == 0 && h.image.closes == 0, "Activity lifecycle never closes process-owned clients");
            int previousUiEvents = h.ui.events.size();
            h.text.emit("generation", event("background_text", "delta", "delta", "背景で受信"));
            h.text.emit("generation", event("background_text", "complete", "text", "背景で受信した完成原稿"));
            h.image.emit(event("background_image", "complete", "image", TestData.object("id", "image_fixture", "source", "api")));
            h.drain();
            check(h.journal.terminalRecords == 2 && !h.runtime.hasWork(), "both background results reach the journal and finish");
            check(h.ui.events.size() == previousUiEvents, "detached UI receives no completion event");
            Activity replacement = new Activity(h.context); ListenerLog nextUi = new ListenerLog();
            h.runtime.attachUi(replacement, nextUi); h.runtime.replay();
            JSONObject snapshot = nextUi.last("runtimeSnapshot").data;
            check(snapshot.optString("activeTextId").isEmpty() && snapshot.optString("activeImageId").isEmpty(), "reattached snapshot has no stale active IDs");
            check(find(snapshot.getJSONArray("records"), "background_text").optString("text").contains("完成原稿"), "reattached snapshot recovers text received with no screen");
            check(find(snapshot.getJSONArray("records"), "background_image").has("image"), "reattached snapshot recovers generated image metadata");
            h.runtime.loadState();
            check(h.journal.merges == 1 && h.text.calls.size() == 1 && h.image.calls.size() == 1, "recovery merges stored results without generating again");
        }
    }

    private static void terminalPersistence() throws Exception {
        for (boolean image : new boolean[]{false, true}) {
            try (Harness h = new Harness()) {
                String id = image ? "image_commit" : "text_commit";
                h.ready(); h.start(image, image ? image(id) : text(id)); h.drain();
                h.journal.terminalEntered = new CountDownLatch(1); h.journal.terminalRelease = new CountDownLatch(1);
                h.emit(image, image ? event(id, "complete", "image", TestData.object("id", "image_committed"))
                        : event(id, "complete", "text", "保存が先、画面通知が後"));
                try {
                    check(h.journal.terminalEntered.await(2, TimeUnit.SECONDS), "terminal reaches journal before UI");
                    check(h.ui.terminals(id) == 0 && h.runtime.hasWork(), "blocked persistence does not publish completion or become idle");
                } finally { h.journal.terminalRelease.countDown(); }
                h.drain();
                int persisted = TestData.trace.indexOf("journal:persist:" + id + ":complete");
                int delivered = TestData.trace.indexOf("ui:" + (image ? "illustration:" : "generation:") + id + ":complete");
                check(persisted >= 0 && delivered > persisted, "durable terminal boundary precedes completion delivery");
                check(h.ui.last(image ? "illustration" : "generation").data.optLong("seq") > 0, "UI receives journal sequence for acknowledgement");
                check(!h.runtime.hasWork() && h.journal.terminalRecords == 1, "successful terminal atomically clears the work slot");
            }
        }
    }

    private static void cancelBeforePreparation() throws Exception {
        for (boolean image : new boolean[]{false, true}) {
            try (Harness h = new Harness()) {
                h.ready(); h.drain();
                try (ControlGate gate = new ControlGate(h)) {
                    h.start(image, image ? image("cancel_image_prepare") : text("cancel_text_prepare"));
                    if (image) h.runtime.cancelImage(); else h.runtime.cancelText();
                }
                h.drain();
                check(h.text.calls.isEmpty() && h.image.calls.isEmpty(), "cancel already requested before preparation prevents API launch");
                check(h.journal.begins == 1 && h.journal.terminalRecords == 1 && !h.runtime.hasWork(), "cancelled preparation begins once and persists one terminal");
            }
        }
    }

    private static void cancelDuringPreparation() throws Exception {
        try (Harness h = new Harness()) {
            h.ready(); h.drain();
            h.journal.beginEntered = new CountDownLatch(1); h.journal.beginRelease = new CountDownLatch(1);
            h.runtime.startText(text("cancel_during_prepare"));
            try {
                check(h.journal.beginEntered.await(2, TimeUnit.SECONDS), "journal preparation is in flight");
                h.runtime.cancelText();
            } finally { h.journal.beginRelease.countDown(); }
            h.drain();
            check(h.text.calls.isEmpty() && h.journal.begins == 1 && !h.runtime.hasWork(), "cancel racing journal preparation cannot launch or rebegin the request");
        }
    }

    private static void cancelReplacementRace() throws Exception {
        for (boolean all : new boolean[]{false, true}) {
            try (Harness h = new Harness()) {
                h.ready(); h.runtime.startText(text("old_text")); h.drain();
                h.text.autoCancel = false;
                h.ui.hook = event -> {
                    if ("generation".equals(event.type) && "old_text".equals(event.data.optString("requestId"))
                            && TestData.isTerminal(event.data.optString("phase"))) h.runtime.startText(text("new_text"));
                };
                try (ControlGate gate = new ControlGate(h)) {
                    h.text.emit("generation", event("old_text", "complete", "text", "先に届いた完成結果"));
                    if (all) h.runtime.cancelAll("fixture stop"); else h.runtime.cancelText();
                }
                h.drain();
                check(h.text.calls.size() == 2 && "new_text".equals(h.runtime.snapshot().optString("activeTextId")), "queued cancel targets the captured old work and preserves replacement");
                check(!h.text.cancelled.contains("new_text"), "new request never receives the old request's cancellation");
                check("complete".equals(h.ui.firstTerminal("old_text").data.optString("phase")), "server completion that wins the race remains complete");
            }
        }
    }

    private static void idleStopRaces() throws Exception {
        try (Harness h = new Harness()) {
            h.ready(); h.drain();
            check(h.runtime.prepareIdleStop(h.service), "idle owned service can enter stop handshake");
            h.runtime.startText(text("after_idle_stop")); h.drain();
            check(h.text.calls.isEmpty() && h.runtime.hasWork(), "new request waits for a fresh service after idle-stop handshake");
            h.runtime.serviceDestroyed(h.service); h.drain();
            check(h.runtime.hasWork() && h.text.cancelled.isEmpty(), "old normal service destruction does not cancel the new pending job");
            ServiceLog replacement = new ServiceLog();
            h.runtime.serviceReady(replacement); h.drain();
            check(h.text.calls.size() == 1, "replacement service dispatches waiting work once");
            check(!h.runtime.prepareIdleStop(h.service), "stale service cannot stop its replacement");
            h.runtime.serviceDestroyed(h.service); h.drain();
            check(h.runtime.hasWork() && h.text.cancelled.isEmpty(), "late old destroy cannot cancel running replacement work");
        }
        try (Harness h = new Harness()) {
            h.ready(); h.drain();
            try (ControlGate gate = new ControlGate(h)) {
                h.runtime.startText(text("before_idle_check"));
                check(!h.runtime.prepareIdleStop(h.service), "registered pending work prevents an idle stop before preparation");
            }
            h.drain();
            check(h.text.calls.size() == 1, "work accepted before idle check still dispatches once");
        }
    }

    private static void duplicateEvents() throws Exception {
        try (Harness h = new Harness()) {
            h.ready(); h.runtime.startText(text("duplicate_text")); h.drain();
            JSONObject complete = event("duplicate_text", "complete", "text", "完成");
            h.text.emit("generation", complete); h.text.emit("generation", complete); h.drain();
            check(h.journal.terminalRecords == 1 && h.ui.terminals("duplicate_text") == 1, "duplicate terminal is neither re-recorded nor re-delivered");
            h.runtime.startText(text("next_text")); h.drain();
            h.text.emit("generation", complete); h.drain();
            check("next_text".equals(h.runtime.snapshot().optString("activeTextId")) && h.ui.terminals("duplicate_text") == 1, "late prior terminal cannot clear the current work");
        }
    }

    private static void serviceExpiryRace() throws Exception {
        try (Harness h = new Harness()) {
            h.ready(); h.runtime.startText(text("expiring_text")); h.drain();
            h.text.autoCancel = false;
            h.ui.hook = event -> {
                if ("generation".equals(event.type) && "expiring_text".equals(event.data.optString("requestId"))
                        && TestData.isTerminal(event.data.optString("phase"))) h.runtime.startText(text("after_expiry_text"));
            };
            try (ControlGate gate = new ControlGate(h)) {
                // The server finished first, but its terminal is waiting in the control queue.
                // Expiry must synchronously revoke readiness and capture only the old Work.
                h.text.emit("generation", event("expiring_text", "complete", "text", "期限切れ前に完成した本文"));
                h.runtime.serviceExpired(h.service, "fixture time limit");
            }
            h.drain();
            check("complete".equals(h.ui.firstTerminal("expiring_text").data.optString("phase")),
                    "server completion queued before expiry remains a completed result");
            check(h.text.calls.size() == 1 && "after_expiry_text".equals(h.runtime.snapshot().optString("activeTextId")),
                    "new work accepted during expiry waits instead of using the expired service's readiness");
            check(!h.text.cancelled.contains("after_expiry_text"), "queued expiry cancellation cannot cancel replacement work");
            h.runtime.serviceDestroyed(h.service);
            h.runtime.serviceExpired(h.service, "late old timeout"); h.drain();
            check(h.runtime.hasWork() && h.text.calls.size() == 1 && h.text.cancelled.isEmpty(),
                    "late old destroy/timeout leaves the waiting replacement intact");
            ServiceLog replacement = new ServiceLog();
            h.runtime.serviceReady(replacement); h.drain();
            check(h.text.calls.size() == 2 && "after_expiry_text".equals(h.text.calls.get(1).optString("requestId")),
                    "replacement readiness dispatches the waiting new request once");
            h.runtime.serviceExpired(h.service, "stale timeout after replacement");
            h.runtime.startImage(image("image_on_replacement")); h.drain();
            check(h.image.calls.size() == 1 && !h.text.cancelled.contains("after_expiry_text"),
                    "stale service expiry cannot revoke a replacement service's readiness");
        }
        try (Harness h = new Harness()) {
            h.ready(); h.drain();
            try (ControlGate gate = new ControlGate(h)) {
                h.runtime.serviceExpired(h.service, "idle timeout");
                h.runtime.startImage(image("image_after_idle_timeout"));
            }
            h.drain();
            check(h.image.calls.isEmpty() && "image_after_idle_timeout".equals(h.runtime.snapshot().optString("activeImageId")),
                    "expiry with no captured work cannot dispatch a simultaneous new job");
            // stopSelfResult(oldStartId) may leave the same Service instance alive for a new
            // start command; a fresh serviceReady must work for that instance as well.
            h.runtime.serviceReady(h.service); h.drain();
            check(h.image.calls.size() == 1 && h.image.cancelled.isEmpty(),
                    "fresh readiness on the surviving Service instance dispatches the new job once");
        }
    }

    private static void invalidRequests() throws Exception {
        List<JSONObject> invalid = new ArrayList<>();
        invalid.add(null); invalid.add(new JSONObject());
        invalid.add(changed(text("invalid_id"), "requestId", "../file"));
        invalid.add(changed(text("invalid_book"), "bookId", ""));
        invalid.add(changed(text("invalid_chapter"), "chapterId", ""));
        invalid.add(changed(text("invalid_prompt_type"), "prompt", 10));
        invalid.add(changed(text("invalid_prompt_empty"), "prompt", "  "));
        invalid.add(changed(text("invalid_prompt_large"), "prompt", repeat('x', 800001)));
        invalid.add(changed(text("invalid_model"), "model", ""));
        invalid.add(changed(text("invalid_model_large"), "model", repeat('x', 301)));
        try (Harness h = new Harness()) {
            for (JSONObject value : invalid) h.runtime.startText(value);
            h.runtime.startImage(changed(image("invalid_image_prompt"), "prompt", repeat('x', 32001)));
            h.drain();
            check(h.context.starts.isEmpty() && h.text.calls.isEmpty() && h.image.calls.isEmpty(), "invalid IDs, fields and size limits stop before FGS or API calls");
            check(h.journal.begins == 0 && !h.runtime.hasWork(), "invalid requests leave no journal or work slot");
            check(h.ui.events.size() == invalid.size() + 1, "each rejected request emits one controlled error event");
        }
    }

    private static void metadataRequest() throws Exception {
        try (Harness h = new Harness()) {
            JSONObject request = text("metadata_titles"); request.remove("chapterId");
            request.put("purpose", "metadata").put("kind", "titles").put("sourceValue", "以前の題名");
            h.runtime.startText(request); h.drain();
            check(h.text.calls.isEmpty() && h.journal.begins == 1, "metadata request can omit chapter but still waits for service");
            h.ready(); h.drain();
            check(h.text.calls.size() == 1 && "metadata_titles".equals(h.text.calls.get(0).optString("requestId")), "metadata invokes the native text client once");
        }
    }

    private static void journalPreparationFailure() throws Exception {
        GenerationJournal.failConstruction = true;
        try (Harness h = new Harness()) {
            h.runtime.startText(text("no_journal")); h.drain();
            check(!h.runtime.snapshot().optBoolean("backgroundReady") && h.context.starts.isEmpty() && h.text.calls.isEmpty(), "journal open failure rejects before service or inference");
            check(!h.ui.last("generation").data.toString().contains("CANARY"), "journal open failure does not expose private exception text");
        } finally { GenerationJournal.failConstruction = false; }
        try (Harness h = new Harness()) {
            h.journal.failBegin = true; h.ready(); h.runtime.startText(text("begin_failure")); h.drain();
            check(h.text.calls.isEmpty() && !h.runtime.hasWork(), "begin persistence failure prevents native inference and releases slot");
            check(!h.ui.last("generation").data.toString().contains("CANARY"), "journal begin failure is sanitized");
        }
    }

    private static void terminalStorageFailure() throws Exception {
        try (Harness h = new Harness()) {
            h.ready(); h.runtime.startText(text("terminal_disk_failure")); h.drain();
            h.runtime.pauseUi(h.owner); h.runtime.detachUi(h.owner); h.drain();
            h.journal.failRecord = true;
            h.text.emit("generation", event("terminal_disk_failure", "complete", "text", "メモリに保持すべき完成本文")); h.drain();
            JSONObject snapshot = h.runtime.snapshot();
            check(!h.runtime.hasWork() && snapshot.optString("activeTextId").isEmpty(), "failed terminal persistence releases the active slot before snapshot");
            check(!snapshot.optString("message").contains("保存しました") && snapshot.optString("message").contains("失敗"), "storage failure never claims the result was durably saved");
            check(find(snapshot.getJSONArray("records"), "terminal_disk_failure").optString("text").contains("完成本文"), "background terminal write failure keeps the journal's in-memory full result visible");
            check(h.runtime.consumeCompletionMessage().contains("失敗"), "notification completion text preserves storage failure");
            Activity owner = new Activity(h.context); ListenerLog ui = new ListenerLog(); h.runtime.attachUi(owner, ui); h.runtime.replay();
            check(find(ui.last("runtimeSnapshot").data.getJSONArray("records"), "terminal_disk_failure").optString("text").contains("完成本文"), "returning UI can recover the in-memory fallback");
        }
    }

    private static void serviceLoss() throws Exception {
        try (Harness h = new Harness()) {
            h.ready(); h.runtime.startText(text("service_text")); h.runtime.startImage(image("service_image")); h.drain();
            h.runtime.serviceDestroyed(h.service); h.drain();
            check(h.text.cancelled.size() == 1 && h.image.cancelled.size() == 1, "unexpected owned service destruction cancels both active clients");
            check(!h.runtime.hasWork() && h.journal.terminalRecords == 2, "service-loss cancellation stores both terminal outcomes");
            check("error".equals(h.ui.firstTerminal("service_text").data.optString("phase")), "service loss is distinguished from explicit user cancellation");
        }
    }

    private static void notificationAndAcknowledgement() throws Exception {
        try (Harness h = new Harness()) {
            h.context.notifications.enabled = false;
            check(!h.runtime.notificationsEnabled(), "notification permission is represented accurately");
            h.ready(); h.runtime.startText(text("notification_denied")); h.drain();
            check(h.text.calls.size() == 1, "hidden notification permission is not confused with FGS start failure");
            h.text.emit("generation", event("notification_denied", "complete", "text", "結果")); h.drain();
            JSONObject terminal = h.ui.firstTerminal("notification_denied").data;
            check(!h.runtime.acknowledge("../invalid", 1) && !h.runtime.acknowledge("notification_denied", -1), "invalid acknowledgement is rejected");
            long seq = terminal.optLong("seq");
            check(!h.runtime.acknowledge("notification_denied", seq) && h.journal.acknowledgements == 0
                    && h.runtime.snapshot().getJSONArray("records").length() == 1,
                    "ACK without a durable manuscript receipt cannot remove the only journal copy");
            h.runtime.drafts().state = TestData.object("runtimeReceipts", TestData.object("notification_denied",
                    TestData.object("terminal", false, "seq", seq))).toString();
            check(!h.runtime.acknowledge("notification_denied", seq) && h.journal.acknowledgements == 0,
                    "a saved nonterminal receipt does not authorize terminal journal removal");
            h.runtime.drafts().state = TestData.object("runtimeReceipts", TestData.object("notification_denied",
                    TestData.object("terminal", true, "seq", seq - 1))).toString();
            check(!h.runtime.acknowledge("notification_denied", seq) && h.journal.acknowledgements == 0,
                    "an older saved receipt cannot acknowledge a newer terminal result");
            h.runtime.drafts().state = TestData.object("runtimeReceipts", TestData.object("notification_denied",
                    TestData.object("terminal", true, "seq", seq))).toString();
            check(h.runtime.acknowledge("notification_denied", seq), "saved terminal receipt authorizes matching request ID and sequence acknowledgement");
            check(h.runtime.snapshot().getJSONArray("records").length() == 0, "acknowledged terminal is omitted from future replay");
            h.context.notifications.enabled = true;
            h.context.notifications.channel = new NotificationChannel(); h.context.notifications.channel.importance = 0;
            check(!h.runtime.notificationsEnabled(), "disabled generation channel is represented accurately");
        }
    }

    private static JSONObject text(String id) {
        return TestData.object("requestId", id, "bookId", "book_fixture", "chapterId", "chapter_fixture",
                "purpose", "manuscript", "mode", "new", "sourceBody", "", "model", "test-model", "instructions", "Write fiction", "prompt", "小説の本文を生成");
    }
    private static JSONObject image(String id) { return TestData.object("requestId", id, "bookId", "book_fixture", "chapterId", "chapter_fixture", "prompt", "小説の挿絵を生成"); }
    private static JSONObject event(String id, String phase, String field, Object value) { return TestData.object("requestId", id, "phase", phase, field, value); }
    private static JSONObject changed(JSONObject value, String field, Object replacement) { try { value.put(field, replacement); return value; } catch (Exception error) { throw new AssertionError(error); } }
    private static String repeat(char value, int count) { char[] result = new char[count]; java.util.Arrays.fill(result, value); return new String(result); }
    private static JSONObject find(JSONArray records, String id) {
        for (int i = 0; i < records.length(); i++) { JSONObject value = records.optJSONObject(i); if (value != null && id.equals(value.optString("requestId"))) return value; }
        throw new AssertionError("missing journal record " + id);
    }
    private static Object field(Object value, String name) throws Exception { Field field = value.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(value); }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); assertions++; }
    private interface Test { void run() throws Exception; }
    private static void scenario(String name, Test test) {
        scenarios++;
        try { test.run(); System.out.println("PASS " + name); }
        catch (Throwable failure) { failures++; System.out.println("FAIL " + name + ": " + failure.getClass().getSimpleName() + ": " + failure.getMessage()); }
    }

    private static final class Event {
        final String type; final JSONObject data;
        Event(String type, JSONObject data) { this.type = type; this.data = TestData.copy(data); }
    }
    private static final class ListenerLog implements GenerationRuntime.UiListener {
        final List<Event> events = new CopyOnWriteArrayList<>();
        Consumer<Event> hook;
        public void onRuntimeEvent(String type, JSONObject data) {
            Event event = new Event(type, data); events.add(event);
            TestData.trace.add("ui:" + type + ":" + data.optString("requestId") + ":" + data.optString("phase"));
            if (hook != null) hook.accept(event);
        }
        Event last(String type) {
            for (int i = events.size() - 1; i >= 0; i--) if (type.equals(events.get(i).type)) return events.get(i);
            throw new AssertionError("missing UI event " + type);
        }
        int terminals(String id) { int result = 0; for (Event event : events) if (id.equals(event.data.optString("requestId")) && TestData.isTerminal(event.data.optString("phase"))) result++; return result; }
        Event firstTerminal(String id) { for (Event event : events) if (id.equals(event.data.optString("requestId")) && TestData.isTerminal(event.data.optString("phase"))) return event; throw new AssertionError("missing UI terminal " + id); }
    }
    private static final class ServiceLog implements GenerationRuntime.ServiceListener {
        int notifications;
        public void onGenerationProgress() { notifications++; TestData.trace.add("service:progress"); }
    }
    private static final class Harness implements AutoCloseable {
        final Context context = new Context(); final Activity owner = new Activity(context);
        final ListenerLog ui = new ListenerLog(); final ServiceLog service = new ServiceLog();
        final GenerationRuntime runtime; final ExecutorService control;
        final SiwcClient text; final ImageGenerationClient image; final GenerationJournal journal;
        Harness() throws Exception {
            TestData.trace.clear();
            Constructor<GenerationRuntime> constructor = GenerationRuntime.class.getDeclaredConstructor(Context.class);
            constructor.setAccessible(true); runtime = constructor.newInstance(context);
            control = (ExecutorService)field(runtime, "control");
            text = runtime.siwc(); image = runtime.imageClient(); journal = (GenerationJournal)field(runtime, "journal");
            runtime.attachUi(owner, ui);
        }
        void ready() { runtime.serviceReady(service); }
        void start(boolean isImage, JSONObject request) { if (isImage) runtime.startImage(request); else runtime.startText(request); }
        void emit(boolean isImage, JSONObject event) { if (isImage) image.emit(event); else text.emit("generation", event); }
        void drain() throws Exception {
            // Callbacks may enqueue terminal processing behind the current executor barrier.
            for (int i = 0; i < 4; i++) control.submit(() -> { }).get(3, TimeUnit.SECONDS);
        }
        public void close() throws Exception {
            control.shutdownNow();
            if (!control.awaitTermination(3, TimeUnit.SECONDS)) throw new AssertionError("runtime executor did not stop after test");
        }
    }
    private static final class ControlGate implements AutoCloseable {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ControlGate(Harness h) throws Exception {
            h.control.execute(() -> { entered.countDown(); try { release.await(4, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); } });
            if (!entered.await(2, TimeUnit.SECONDS)) throw new AssertionError("control gate was not reached");
        }
        public void close() { release.countDown(); }
    }
}
