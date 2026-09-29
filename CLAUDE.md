# Harmonium Host: project context

Native Android shell for Sanytron Astrion HA100 remotes. It hosts Harmonium (github.com/skavan/harmonium, a Home Assistant remote-control frontend) in a WebView and adds the native pieces Fully Kiosk + Key Mapper can't do well. **The code in this repo is the source of truth**; this file records intent, measured facts and decisions from the design phase.

## Why this exists (gaps vs. Fully Kiosk)
1. **Mic button doesn't work.** Browsers need HTTPS for `getUserMedia`; HA here is plain `http://`. Fix: capture natively with `AudioRecord`, so no secure context is needed.
2. **Screen sleeps while idle**, and the wake press is swallowed, followed by a reconnect delay. Goal: screen/connection already awake by the time a thumb lands.
3. **No charging feedback on the cradle.** The stock Astrion app flashes a charging screen; Fully Kiosk doesn't.

Prefer local/native solutions. No cloud dependencies.

## Architecture
- `MainActivity`: immersive full-screen WebView loading `<ha_url><harmonium_path>`. Never pauses the WebView (keeps Harmonium's websocket alive through screen-off). Logs every key event (tag `HarmoniumHost`). Consumes the mic key for push-to-talk. Runs keys through `KeyRemap`. Overlays above the WebView: a tiny battery readout (top edge, not touchable), the voice status, and `ChargingScreen`. Holding the battery readout for 3 s opens Settings (the gesture is watched, never consumed). With no token of its own, it adopts Harmonium's paired token from the page's `localStorage.hakr_token`.
- `HostService`: foreground service. Runtime-registered receivers (manifest receivers don't get these since API 26) for power connect/disconnect, `BATTERY_CHANGED` and screen on/off. Wakes the screen on connect (charging screen) and disconnect. Registers the proximity sensor (wake-up) and wakes the screen on any near/far change while it's off. While the screen is on: holds the high-perf Wi-Fi lock and wants the `HaLink`.
- `HostState`: process-wide state and events (battery, charging, activity, HA link, proximity, screen on). Service writes, activity listens. Main thread only.
- `HaLink`: one shared native HA websocket (auth, id-routed replies, binary frames). Connected only while wanted ("screen", "voice"); closes 60 s after the last want drops, so a sleeping remote pays no heartbeats. Reconnects with backoff.
- `ActivityWatcher`: `subscribe_entities` on `select.harmonium_<room>_activity` via `HaLink`. Harmonium's select has activity ids as options and `off` when idle.
- `ScreenKeeper`: first-press-always-works. `FLAG_KEEP_SCREEN_ON` while keep-awake applies (default: activity running), dims via window `screenBrightness` after N s, releases after M minutes without a press (no limit on the cradle by default). Any key, touch, screen-on, proximity or unplug counts as a press. `postDelayed` only, no polling.
- `KeyRemap`: replaces Key Mapper. Text rules (`<key> <short> [<long>]`, KeyEvent names), editable in Settings. Keys with a long-press rule emit on release or at the long-press time (Harmonium fires taps on keydown and leaves holds to the shell). Defaults mirror harmonium's `remotes/astrion/keymapper/v2` export (the Key Mapper rules there are scoped to Fully Kiosk being in the foreground, so they don't fire in this app).
- `VoiceRouter`: push-to-talk (hold, speak, release). Default: Assist. Optional Siri routing (Settings) reads the per-remote HA `input_boolean` at key-down:
  - **on → Siri:** stream raw PCM16/16 kHz/mono as a chunked POST to `<ha_url>/api/appletv_siri/audio/<apple_tv>?route=siri` (Bearer token). End of body = end of utterance.
  - **Assist:** `assist_pipeline/run` over `HaLink` (start_stage stt, end_stage tts, `input: {sample_rate: 16000, no_vad: true}` since the key release ends the utterance). `run-start` returns `stt_binary_handler_id`. Send each audio frame prefixed with that one byte; a frame containing only the id byte ends the audio. Play the `tts-end` URL on the remote's speaker. Reuses `conversation_id` for 5 min. Presses under 0.3 s are dropped with a hint.
- `HostPrefs` + `SettingsActivity`: every setting is saved on the remote and edited in Settings (also in the app drawer as "Harmonium settings"). adb `--es` extras still work for `ha_url, token, room, harmonium_path, harmonium_profile, activity_entity, pipeline, apple_tv, siri_entity`. The first load after a URL/token/profile change passes `#host=…&token=…&device=<profile>` to Harmonium's own one-time provisioning. "Test connection" checks token, activity entity and lists Assist pipelines (flags ones without STT).
- `ha/packages/remote_voice.yaml`: only for optional Siri routing. `input_boolean.remote_siri_<room>` plus an automation following the activity select. The activity id in it is a placeholder.

Voice backends:
- Assist (default): needs a pipeline with an STT engine (HA's default pipeline may not have one). No HA device or entity is needed; the remote isn't a device in an area, so commands must name the area.
- Siri (optional): github.com/marcusadolfsson/appletv-siri-voice (Docker bridge with host networking + HA custom integration). One utterance in flight at a time across all Apple TVs. The Apple TV must be awake. The integration's own `siri_when` routing is deliberately NOT used: it's one global rule, and its Assist fallback returns JSON rather than audio to the caller.

## Why the first press gets lost, and the fix
On Android 8.1, when the screen is off `PhoneWindowManager.interceptKeyBeforeQueueing` spends a wake key on waking and doesn't pass it to apps; accessibility filters (Key Mapper) don't get it either. So the only fix is a screen that is on (interactive) when the key lands: `ScreenKeeper` keeps it on, dimmed, while an activity runs; proximity and unplug wake it early. Once `ScreenKeeper` lets go (idle limit), Android's own screen timeout applies. Idea not built yet: volume and media keys are *not* wake keys and reach an active `MediaSession` even with the screen off, so a remote-volume session could make volume work with the screen off.

## Target hardware (measured on a real unit)
- Astrion HA100: Android **8.1.0 (API 27)**, **armeabi-v7a**, MediaTek MT6580 (2015-era, weak). Keep native work light. On-device wake words are unrealistic → push-to-talk.
- WebView: `com.google.android.webview` **136.0.7103.61** (modern Chromium; Harmonium's ES2019 target is no problem).
- Stock launcher package: `com.aiks.HaRemote` (fallback if this app is uninstalled or not set as HOME).
- Sensors: exactly three. ACCELEROMETER (non-wakeUp, 1–100 Hz), LIGHT (non-wakeUp), PROXIMITY (**wakeUp**). No gyro, no tilt/pickup/significant-motion.
- **Mic key = `KEYCODE_F3` (134)**, per Key Mapper. That's the default; Settings can learn another key (keycode, or scancode if the keycode is UNKNOWN).
- Raw keys (from harmonium `remotes/astrion`): Home F1, Power F2, F4–F7 (lightbulb/curtains/music/climate or REW/PlayPause/Stop/FWD), colour keys F8–F11 (Key Mapper's *global* app-launcher rules still fire here), Back, Menu, Vol±, Mute, D-pad, Enter, PageUp/PageDown (CH±).
- Key Mapper's accessibility service can consume keys before this app sees them. Remove or disable its triggers for any key handled here. Its Fully-scoped rules don't fire in this app.
- `adb shell getevent -lt` needs keys pressed while it runs (the first attempt was interrupted before any key press).

## Wake strategy (implemented: options 1 + 2)
The accelerometer is non-wakeUp, so pickup detection by accelerometer can't work while suspended. Built:
1. **Proximity sensor (wakeUp)**, on-change: any near/far change wakes the screen if it's off (at most every 3 s) and brightens it if it's dimmed. Every event is logged with the screen state, and Settings → Status shows the last one. **Still to verify on the device:** does reaching for or lifting the remote actually toggle it? Is the driver still active while suspended?
2. **Keep the screen on, dimmed**, while an activity runs (`ScreenKeeper`, defaults: dim after 15 s, release after 20 min without a press, no limit on the cradle).
3. Not built: partial wake lock + non-wakeUp accelerometer. The CPU never sleeps, so measure battery first.

## Constraints and gotchas
- `usesCleartextTraffic="true"` is required (HA is http/ws on the LAN).
- minSdk/targetSdk 27. Use `ContextCompat.registerReceiver` with `RECEIVER_NOT_EXPORTED`.
- `chrome://inspect` works (WebView debugging is enabled) for debugging Harmonium on the device.
- After a Harmonium engine change: reload/clear cache on the remote. After an integration `.py` change: restart HA.
- Don't put the long-lived token in logs, commits or chat. Pairing through Harmonium (adopted automatically), the Settings screen, or adb.
- Android 8.1 can't resolve `.local` hostnames: the HA URL must be an IP address.
- No Android SDK in the cloud sandbox (dl.google.com is blocked). Kotlin was type-checked there with kotlinc against Robolectric's `android-all:8.1.0` jar and stubbed androidx classes, but not built with Gradle. Build and lint in Android Studio.

## Licensing (matters if borrowing code)
- Harmonium: no license chosen, all rights reserved. Load it from the user's own HA; don't bundle it in the APK. Author's roadmap mentions voice via the IME trick and a minimal APK shell, so coordinate if it gets serious.
- Kiosk Satellite: CC BY-NC-ND 4.0. Read for ideas only; don't copy code.
- Ava (brownard/Ava, an ESPHome-protocol voice satellite in Kotlin, and knoop7/Ava): check the license before reusing anything.
- appletv-siri-voice: Apache-2.0.

## Roadmap
1. Verify push-to-talk end to end with Assist (F3 held).
2. Charging screen, battery readout and unplug wake: verify on the cradle.
3. Proximity: read the logs to see whether it fires when reaching for or lifting the remote. Keep-awake: measure battery over an evening.
4. Verify the default key rules against Harmonium's keymap; then retire Key Mapper's Fully-scoped rules.
5. Maybe: MediaSession remote-volume so volume works with the screen off (see "Why the first press gets lost").
6. Uncomment the HOME intent filter so it replaces Fully Kiosk as the launcher.
7. Later: implement the ESPHome native API in-app so each remote appears in HA as a device with an `assist_satellite` entity plus battery/charging/screen entities and a "Siri mode" switch replacing the `input_boolean`.

## Dev workflow
```bash
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
adb connect <remote-ip>:5555                      # after `adb tcpip 5555` over USB once per reboot
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.harmoniumhost/.MainActivity --es token "<LLAT>" --es room great_room
adb shell am start -n com.example.harmoniumhost/.SettingsActivity   # or hold the battery readout 3 s
adb logcat -s HarmoniumHost
```
