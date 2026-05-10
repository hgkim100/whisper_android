# Manual smoke test — Whisper Android

End-to-end validation procedure that exercises the full
`download → load → record → stop → transcribe → result` pipeline on a real
device or emulator. Use this before tagging a release, or whenever the
release-mode (R8 / resource-shrunk) APK changes.

The unit-test suite covers component-level correctness (19 tests at the time
of writing — model downloader, manifest parser, fake transcriber, view-model
state machine). This document covers the things tests *can't* prove: real
JNI / native-library loading, model download over the network, microphone
input → text output through the actual whisper.cpp inference path, and that
ProGuard rules survive minification.

---

## Prerequisites

- Android Studio AVD running, **or** physical device connected over `adb`
- `adb devices` lists exactly one target
- App's release APK built and signed (see README §"Building a release APK")
  — debug APK works for everything except the post-R8 sanity scenarios

If using an emulator, enable microphone passthrough in AVD's *Extended
Controls → Microphone* and grant Android Studio access to your host mic.
Most emulator AVDs default to a silent virtual microphone, in which case
inference still runs but produces `[BLANK_AUDIO]` / `[inaudible]` markers
rather than transcribed speech — that's enough to prove the pipeline is
wired up correctly; for actual transcription accuracy use a physical device.

---

## Scenario 1 — First run (cold install)

Verifies: permission request flow, model download progress, JNI native lib
load, transcription path.

```bash
adb uninstall com.hgkim.whisperandroid     # discard any prior model file
adb install -r app/build/outputs/apk/release/app-release.apk
adb logcat -c
adb shell am start -n com.hgkim.whisperandroid/.MainActivity
adb logcat | grep -E 'WhisperApp|MainViewModel|WhisperEngine|whisper_jni|ModelDownloader|FATAL|AndroidRuntime'
```

Expected sequence:

1. App launches, lands on `Status: Ready` with a single **Record** button. No
   FATAL / AndroidRuntime exceptions in logcat.
2. Tap **Record** → permission dialog appears. **Allow.**
3. UI transitions to `Status: Downloading model NN%`. Progress increments
   monotonically until 100 % (~75 MB; 30 – 90 s on a fast connection).
4. UI transitions to `Status: Recording` (Stop button). logcat:
   - `nativeloader: Load … libwhisper_jni.so … ok`
   - `whisper_jni: init: whisper context loaded, ptr=0x…`
5. Speak a short English sentence ≤ 60 s, then tap **Stop**.
6. UI transitions to `Status: Transcribing`, then to `Status: Done` with the
   transcript text in a `SelectionContainer` (long-press to copy).

> *Silent emulator mic*: on a virtual AVD with no mic input the transcript
> reads `[BLANK_AUDIO]` or `[inaudible]` — that's whisper.cpp's expected
> output for silence, not a bug.

---

## Scenario 2 — Permission denial recovery

Verifies: `Error(PermissionDenied)` UI, "Open Settings" affordance,
`onResume` recovery to `Idle`.

1. From a fresh install, tap **Record**, then **Deny** on the permission
   dialog.
2. UI transitions to `Status: Permission denied` with the explanation text
   and an **Open Settings** button.
3. Tap **Open Settings**. System Settings opens on the app's permission page.
4. Toggle **Microphone** on, return to the app (back button).
5. UI auto-transitions back to `Status: Ready` (no manual tap required) —
   that's the `onResume` permission-recovery path added in Task #9.

---

## Scenario 3 — 60-second auto-stop

Verifies: `PcmBuffer`'s 960 000-sample cap, `MainViewModel`'s natural-
completion → auto-`stopAndTranscribe()` path.

1. From `Idle` (model already on disk), tap **Record** and don't stop.
2. After exactly 60 s of capture the UI auto-transitions to
   `Status: Transcribing`, then `Status: Done`.
3. logcat shows the same `whisper_jni init` / inference path as scenario 1,
   triggered without a Stop tap.

`MainViewModel` enforces the cap by checking the in-flight state
(`if (state is Recording)`) and re-throwing `CancellationException` first,
so a *user-initiated* stop and the *natural-completion* path can't race —
exactly one of them takes the transcription branch.

---

## Scenario 4 — Repeat (warm cache)

Verifies: model file caching, single-flight chain guard.

1. From the `Result` state of any previous scenario, tap **Record** again.
2. Expected: UI skips `Downloading model` (the file is on disk), goes
   straight to `Recording`.
3. While in any `Downloading` / `Transcribing` state, the mic button is
   disabled — confirming the `MainViewModel.chainJob` single-flight guard.

---

## What "good" looks like — log capture

Save a clean capture (logcat + uiautomator dump) for the release notes:

```bash
adb logcat -d > /tmp/smoke-$(date +%Y%m%d-%H%M%S).log
adb shell uiautomator dump && adb pull /sdcard/window_dump.xml /tmp/
```

Look for:

- `nativeloader: Load … libwhisper_jni.so … ok` (×1 per launch)
- `whisper_jni: init: whisper context loaded, ptr=0x…` (×1 per fresh chain)
- No `FATAL EXCEPTION`, no `UnsatisfiedLinkError`, no `OutOfMemoryError`
- No `IllegalStateException: CompositionLocal LocalLifecycleOwner not present`
  (this would indicate a missing ProGuard keep rule — see
  `app/proguard-rules.pro` §3)

---

## Failure triage

| Symptom | Likely cause | Fix |
|---|---|---|
| `UnsatisfiedLinkError` on `WhisperJni.init` | R8 stripped JNI symbols | Verify §1 of `proguard-rules.pro` is intact |
| `CompositionLocal LocalLifecycleOwner not present` | R8 stripped activity-compose's setContent provider lambda | Verify §3 of `proguard-rules.pro` is intact |
| `Status: Downloading model` stuck | Network / Hugging Face mirror unreachable | Check `assets/models.json` URL, retry with `Retry download` |
| Empty / `[BLANK_AUDIO]` transcript on a real device | Mic permission not actually granted, or AVD mic muted | Re-check via `adb shell dumpsys package com.hgkim.whisperandroid \| grep -i record_audio` |
| `Out of memory` error message | Too-large recording buffer + low-memory device | Try a shorter take (the message itself is the new OOM-catch path) |
