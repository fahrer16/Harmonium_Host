# Harmonium Host: project context

Native Android shell for Sanytron Astrion HA100 remotes. It hosts Harmonium (github.com/skavan/harmonium, a Home Assistant remote-control frontend) in a WebView and adds the native pieces Fully Kiosk + Key Mapper can't do well. **The code in this repo is the source of truth**; this file records intent, measured facts and decisions from the design phase.

## Why this exists (gaps vs. Fully Kiosk)
1. **Mic button doesn't work.** Browsers need HTTPS for `getUserMedia`; HA here is plain `http://`. Fix: capture natively with `AudioRecord`, so no secure context is needed.
2. **Screen sleeps while idle**, and the wake press is swallowed, followed by a reconnect delay. Goal: screen/connection already awake by the time a thumb lands.
3. **No charging feedback on the cradle.** The stock Astrion app flashes a charging screen; Fully Kiosk doesn't.

Prefer local/native solutions. No cloud dependencies.

## Architecture
- `MainActivity`: immersive full-screen WebView loading `<ha_url>/local/harmonium/index.html`. Never pauses the WebView (keeps Harmonium's websocket alive through screen-off). Logs every key event (tag `HarmoniumHost`). Consumes the mic key for push-to-talk. Has a `remap` table (vendor scancode → standard keycode) replacing Key Mapper. Shows a native overlay TextView above the WebView for charging and voice status.
- `HostService`: foreground service. Runtime-registered `ACTION_POWER_CONNECTED/DISCONNECTED` receivers (manifest receivers don't get these since API 26). Wakes the screen on connect (short) and disconnect (longer), shows the charging overlay, holds a Wi-Fi lock, and has an experimental pickup-wake (see sensor findings: needs rework).
- `VoiceRouter`: push-to-talk (hold, speak, release). Reads a per-remote HA `input_boolean` at key-down:
  - **on → Siri:** stream raw PCM16/16 kHz/mono as a chunked POST to `<ha_url>/api/appletv_siri/audio/<apple_tv>?route=siri` (Bearer token). End of body = end of utterance.
  - **off → Assist:** HA websocket `assist_pipeline/run` (start_stage stt, end_stage tts, `input.sample_rate` 16000). `run-start` returns `stt_binary_handler_id`. Send each audio frame prefixed with that one byte; a frame containing only the id byte ends the audio. Play the `tts-end` URL on the remote's speaker.
- `HostPrefs`: settings provisioned over adb intent extras (no settings UI yet). Keys: `ha_url, token, room, apple_tv, pipeline, harmonium_path, siri_entity`. First load passes `#host=…&token=…&device=astrion` to Harmonium's own one-time provisioning.
- `ha/packages/remote_voice.yaml`: `input_boolean.remote_siri_<room>` plus an automation that follows `select.harmonium_<room>_activity` (turn on for the Apple TV activity, off otherwise). Per-room copy/paste. Activity names in it are placeholders.

Voice backends:
- Siri: github.com/marcusadolfsson/appletv-siri-voice (Docker bridge with host networking + HA custom integration). One utterance in flight at a time across all Apple TVs. The Apple TV must be awake. The integration's own `siri_when` routing is deliberately NOT used: it's one global rule, and its Assist fallback returns JSON rather than audio to the caller.
- Assist: the pinned pipeline must have an STT engine (HA's default pipeline may not).

## Target hardware (measured on a real unit)
- Astrion HA100: Android **8.1.0 (API 27)**, **armeabi-v7a**, MediaTek MT6580 (2015-era, weak). Keep native work light. On-device wake words are unrealistic → push-to-talk.
- WebView: `com.google.android.webview` **136.0.7103.61** (modern Chromium; Harmonium's ES2019 target is no problem).
- Stock launcher package: `com.aiks.HaRemote` (fallback if this app is uninstalled or not set as HOME).
- Sensors: exactly three. ACCELEROMETER (non-wakeUp, 1–100 Hz), LIGHT (non-wakeUp), PROXIMITY (**wakeUp**). No gyro, no tilt/pickup/significant-motion.
- **Mic key = `KEYCODE_F3` (134)**, per Key Mapper. Set `MIC_KEYCODE = KeyEvent.KEYCODE_F3` in `MainActivity` (scancode not yet captured). Other keys are still unmapped; identify them via Logcat or Key Mapper's trigger recorder.
- Key Mapper's accessibility service can consume keys before this app sees them. Remove or disable its triggers for any key handled here.
- `adb shell getevent -lt` needs keys pressed while it runs (the first attempt was interrupted before any key press).

## Known issue: pickup-wake as written cannot work
`HostService` requests `getDefaultSensor(TYPE_ACCELEROMETER, wakeUp = true)`, which returns null on this device (accelerometer is non-wakeUp), so it just logs a warning. Non-wakeUp sensors stop delivering when the CPU suspends. Options, in the order to try:
1. **Proximity sensor (wakeUp).** Cheap experiment: register it, log near/far, and see whether reaching for or picking up the remote toggles it. If so, use it to wake the screen. It's on-change, so it costs almost nothing.
2. **Don't sleep at all** while an activity is running and the remote is off the cradle: keep the screen on at minimum brightness/black overlay, with a timeout. This removes the swallowed-first-press problem entirely, at some battery cost. Would need the app to know activity state (e.g. its own HA websocket subscription to `select.harmonium_<room>_activity`).
3. Partial wake lock + non-wakeUp accelerometer while off-cradle. Works, but the CPU never sleeps; measure battery first.

## Constraints and gotchas
- `usesCleartextTraffic="true"` is required (HA is http/ws on the LAN).
- minSdk/targetSdk 27. Use `ContextCompat.registerReceiver` with `RECEIVER_NOT_EXPORTED`.
- `chrome://inspect` works (WebView debugging is enabled) for debugging Harmonium on the device.
- After a Harmonium engine change: reload/clear cache on the remote. After an integration `.py` change: restart HA.
- Don't put the long-lived token in logs, commits or chat. Provision via adb only.

## Licensing (matters if borrowing code)
- Harmonium: no license chosen, all rights reserved. Load it from the user's own HA; don't bundle it in the APK. Author's roadmap mentions voice via the IME trick and a minimal APK shell, so coordinate if it gets serious.
- Kiosk Satellite: CC BY-NC-ND 4.0. Read for ideas only; don't copy code.
- Ava (brownard/Ava, an ESPHome-protocol voice satellite in Kotlin, and knoop7/Ava): check the license before reusing anything.
- appletv-siri-voice: Apache-2.0.

## Roadmap
1. Set `MIC_KEYCODE`, verify push-to-talk end to end on both routes (Siri toggle on/off).
2. Charging overlay and unplug wake: verify on the cradle.
3. Wake strategy per the section above; measure battery.
4. Fill the `remap` table for other keys; retire Key Mapper.
5. Uncomment the HOME intent filter so it replaces Fully Kiosk as the launcher.
6. Later: implement the ESPHome native API in-app so each remote appears in HA as a device with an `assist_satellite` entity plus battery/charging/screen entities and a "Siri mode" switch replacing the `input_boolean`.

## Dev workflow
```bash
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
adb connect <remote-ip>:5555                      # after `adb tcpip 5555` over USB once per reboot
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.harmoniumhost/.MainActivity --es token "<LLAT>" --es room great_room --es apple_tv great_room
adb logcat -s HarmoniumHost
```
