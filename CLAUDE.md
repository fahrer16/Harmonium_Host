# Harmonium Host: project context

Native Android shell for Sanytron Astrion HA100 remotes. It hosts Harmonium (github.com/skavan/harmonium, a Home Assistant remote-control frontend) in a WebView and adds the native pieces Fully Kiosk + Key Mapper can't do well. **The code in this repo is the source of truth**; this file records intent, measured facts and decisions from the design phase.

## Why this exists (gaps vs. Fully Kiosk)
1. **Mic button doesn't work.** Browsers need HTTPS for `getUserMedia`; HA here is plain `http://`. Fix: capture natively with `AudioRecord`, so no secure context is needed.
2. **Screen sleeps while idle**, and the wake press is swallowed, followed by a reconnect delay. Goal: screen/connection already awake by the time a thumb lands.
3. **No charging feedback on the cradle.** The stock Astrion app flashes a charging screen; Fully Kiosk doesn't.

Prefer local/native solutions. No cloud dependencies.

## Architecture
- `MainActivity`: immersive full-screen WebView (`VerticalWebView`: horizontal page scroll clamped to 0, plus injected `overflow-x:hidden` CSS; no zoom) loading `<ha_url><harmonium_path>#device=<profile>&page=<start_page>` on every load (plus `host`/`token` once after they change). Never pauses the WebView (keeps Harmonium's websocket alive through screen-off). Logs every key event (tag `HarmoniumHost`). Consumes the mic key for push-to-talk. Runs keys through `KeyRemap`. Overlays above the WebView: a tiny battery readout (top edge, not touchable), `Screensaver`, the voice status, and `ChargingScreen`. Settings opens on a swipe down from the top edge (starts within 36 dp, travels 90 dp) or a 1 s hold within 64 dp of it (moving under 16 dp); both are watched, never consumed. The Astrion firmware took the swipe for its own settings panel (field report 3); the app can now be the HOME app, which may stop that. After the first save it asks once each for: default home, Usage access, screen capture (`askSetupQuestions`). Carries out HA commands arriving as `HostState` events (reload, clear cache, screensaver, black "screen off", settings changed), provides the fallback screenshot (root view drawn to a JPEG; `ScreenCapture`/MediaProjection is preferred and shows any app), and reports the page URL (token fragment stripped). Reads the sticky battery intent itself on resume, so the readout doesn't depend on the service.
- `HostService`: foreground service. Runtime-registered receivers (manifest receivers don't get these since API 26) for power connect/disconnect, `BATTERY_CHANGED` and screen on/off. Wakes the screen on connect (charging screen) and disconnect. Registers the proximity sensor (wake-up) and wakes the screen on any near/far change while it's off. Holds the high-perf Wi-Fi lock only while the screen is on. (The light sensor was dropped: Android lists one, but it never reported a reading.) Tracks network uptime. Creates `RemoteDevice` and starts `EspServer`.
- `EspServer` + `EspProto`: the ESPHome native API, plaintext (no Noise), port 6053, announced over mDNS (`_esphomelib._tcp` via `NsdManager`, TXT `mac`/`friendly_name`/`version`). HA is the client. Speaks API 1.12 (≥1.10 so `voice_assistant_feature_flags` is read; <1.14 so object_id is still used; <1.15 so HA doesn't ask for DeviceCapabilities). Device info: made-up stable locally-administered MAC (apps can't read the real one), voice features `VOICE_ASSISTANT|API_AUDIO` (no SPEAKER: HA sends the reply as a `url` in the TTS_END event). Generic entities (`EspEntities.kt`: sensor, binary_sensor, text_sensor, switch, number, select, text, button, camera) with read/write lambdas; `refresh()` re-reads all and sends only changed states, coalesced, plus every 60 s. **Every socket write goes through one writer thread** (`io`): the first device test crashed with NetworkOnMainThreadException when a battery broadcast wrote to the socket. Asks HA to forward entity states/attributes via `SubscribeHomeAssistantStateResponse` (activity select; weather condition/temperature) with no token or permission; `resubscribe()` sends only pairs not yet sent on that connection (subscriptions last until HA disconnects), so a new activity/weather entity needs no reconnect. Timestamps are text_sensors with device_class timestamp (ISO strings), because sensor states are float32. Screenshots go out as `CameraImageResponse` in 8 KB chunks; only the "Take screenshot" button captures, and HA's camera requests re-send the last capture, at most every 5 s per connection. HA's live view asks for the next frame the moment one lands: answered at full speed (0.4) that kept two threads copying images nonstop, which was the likely cause of field report 4's GC storm (heap at the 128 MB cap) and sluggishness. User services (`EspService`, message 41/42, string args) and device-initiated HA actions (`callHaAction`, message 35 with `data_template`; sent 3 s after HA subscribes to services, message 34, so HA has registered the device's services). Tested against the real `aioesphomeapi` client (handshake, entities, states, HA-state forwarding, full voice exchange, keepalive pings).
- `VoiceSatellite`: push-to-talk over `EspServer`. Key down: `VoiceAssistantRequest(start)` (reuses `conversation_id` for 5 min), wait for `VoiceAssistantResponse`, stream PCM16/16 kHz/mono as `VoiceAssistantAudio` (50 ms chunks), key up: `VoiceAssistantAudio(end=true)`. Presses under 0.3 s send `start=false` (abort). Events shown on the overlay; TTS_END URL played with MediaPlayer (`.local` hosts rewritten to the configured HA URL).
- `RemoteDevice`: the entity list (controls, buttons, screenshot camera, sensors, settings, diagnostics), the HA-state subscriptions, and the actions (wake, device-admin `lockNow`, bring to front, restart via AlarmManager + kill). Screen off without device admin falls back to a black screensaver at minimum brightness. Brightness/adaptive/timeout need WRITE_SETTINGS; foreground app needs Usage access (else "this app"/"another app"); CPU reads /proc/stat, which Android 8 usually blocks, so it falls back to this app's share.
- `Screensaver`: black, clock (TextClock, ticks per minute), or clock + weather; content drifts each minute. Shown by HA's switch, or when dimmed if "Show it when the screen dims" is on. A tap hides it (swallowed); a key hides it and still reaches Harmonium.
- `HarmoniumStyle`: Harmonium's tokens.css palette (bg `#0a0b0d`, tile `#171e27`, control `#222b36`, text `#f2f5f8`/`#98a2ae`, accent `#ffb020`, 12 dp radius) and drawables with the amber D-pad focus ring. Settings uses it.
- `HostState`: process-wide state and events (battery, charging, activity, ESPHome connection, voice ready, proximity, screen on). Service writes, activity listens. Main thread only.
- `ScreenKeeper`: first-press-always-works. `FLAG_KEEP_SCREEN_ON` while keep-awake applies (default since 0.4: on the cradle only; also: activity running, always, never), dims via window `screenBrightness` after N s, releases after M minutes without a press (no limit on the cradle by default). Any key, touch, screen-on, proximity or unplug counts as a press. `postDelayed` only, no polling.
- `KeyRemap`: replaces Key Mapper. Text rules (`<key> <short> [<long>]`, KeyEvent names), editable in Settings. Keys with a long-press rule emit on release or at the long-press time (Harmonium fires taps on keydown and leaves holds to the shell). Defaults mirror harmonium's `remotes/astrion/keymapper/v2` export (the Key Mapper rules there are scoped to Fully Kiosk being in the foreground, so they don't fire in this app).
- Entity dropdowns: the ESPHome API can't list HA entities, so on each connection `RemoteDevice.requestEntityLists` asks HA to call the device's own `esphome.<name>_entity_lists` service with templates HA renders (`states.weather`, `states.select` matching `_activity$`). New lists are saved and the device reconnects so HA re-lists the select options. Needs HA's per-device option "Allow the device to perform Home Assistant actions"; without it the selects offer only the current value.
- `ScreenCapture` + `CaptureConsentActivity` (own task affinity, translucent): MediaProjection kept idle; a virtual display + ImageReader exists only for one frame. Consent is asked again at each app start once granted (silent if "Don't show again" was ticked). "Take screenshot" without consent opens the dialog, then captures.
- `SystemAccess`: default-home check and picker, Usage access check and screen. Foreground app reads usage events incrementally (first look-back 24 h).
- Settings entities (keys 80–99, entity category config): the Settings screen's values, editable from HA. A write saves the pref, then `HostService.reload` + `HostState.Event.SETTINGS_CHANGED` re-apply it (page settings set `reload_pending`). Not exposed: token (would be a plain state), ESPHome names (renaming from HA breaks HA's connection), mic key, key rules (HA text is capped at 255). Removed in 0.4: "Last seen" (a timestamp text sensor changed every minute and flooded HA's logbook) and "Ambient light" (never reported). Removed in 0.5: "Last interaction" (same logbook noise). Added in 0.5: "App memory" (Java heap in use; the cap is 128 MB).
- `HostPrefs` + `SettingsActivity`: every setting is saved on the remote and edited in Settings (also in the app drawer as "Harmonium settings"; buttons for Android settings, Wi-Fi and the three optional permissions: device admin, modify system settings, usage access). adb `--es` extras still work for `ha_url, token, room, harmonium_path, harmonium_profile, start_page, activity_entity, esp_name, esp_friendly_name`.

Voice: HA's Assist through the ESPHome satellite only (the Siri/appletv_siri route and the direct websocket Assist route were removed). HA side: accept the discovered device (or add ESPHome with the remote's IP, port 6053), set its area and Assistant pipeline on the device page. The pipeline needs an STT engine.

Harmonium facts that matter here (from its source): the remote profile (`#device=`) decides capabilities; D-pad passthrough to TVs needs `physical_dpad`, which the `astrion`/`astrion2` profiles have and the default profile doesn't. Under Fully Kiosk Harmonium picks the profile marked `fully: true` automatically (it sees `window.fully`); in this WebView it must be passed. Harmonium's own edge swipes (from within 28 px of either edge) navigate parent/detail and "rubber-band" the grid 18 px when there's no target: that's Harmonium's design, not page scrolling.

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
2. **Keep the screen on, dimmed** (`ScreenKeeper`). Since 0.4 only on the cradle by default (dim after 15 s, screensaver when dimmed). 0.3 kept it on while an activity ran, 20 min after each press: the screen never seemed to turn off, so lift-wake was never seen, and the battery drained fast (field report 3). `HostPrefs.migrate()` moves saved "activity" to "cradle" once. Settings no longer sets `FLAG_KEEP_SCREEN_ON` either.

Field report 4 (build 0.4, 2026-09-30): Settings wouldn't open (neither the hold nor HA's button), the heap sat at 127/128 MB with back-to-back blocking GCs, and the remote was sluggish. Screenshots showed only this app; Foreground app said this app or "another app" (no Usage access). 0.5: camera throttle, MediaProjection screenshots, usage-access and home prompts, swipe back alongside the hold, HOME intent filter on. **To verify:** App memory stays flat; Settings opens; with the app as home, does the top swipe still open the Astrion panel?

Field report 3 (build 0.3.1, 2026-09-30): opens and runs. The top-edge swipe opened the Astrion firmware's own settings panel instead of ours (fixed in 0.4 with a hold). "Ambient light" stayed unknown (the listed LIGHT sensor never reports). "Last seen" flooded HA's logbook. The screen no longer turned off and back on when picked up, the screensaver never showed, and the battery drained fast: all from keep-awake "activity" (see 2). **To verify on 0.4:** off the cradle, does the screen time out, and does lifting wake it again? If lift-wake is gone, check whether the stock app `com.aiks.HaRemote` (maybe the source of lift-wake) is still running.

Field report 2 (build 0.2, 2026-09-29): push-to-talk through the ESPHome satellite works end to end (HA 2026.9.4; TTS reply plays). Proximity registered (`PROXIMITY, wakeUp=true, range=1.0`).

First field report: no proximity events when lifting the remote, yet the screen turns on when it's lifted. Unknown whether that was this build (the report also matched the previous build's behaviour). Something in the firmware wakes on lift; `adb shell getevent -lt` while lifting (off the cradle) would show whether an input device reports it.
3. Not built: partial wake lock + non-wakeUp accelerometer. The CPU never sleeps, so measure battery first.

## Constraints and gotchas
- `usesCleartextTraffic="true"` is required (HA is http/ws on the LAN).
- minSdk/targetSdk 27. Use `ContextCompat.registerReceiver` with `RECEIVER_NOT_EXPORTED`.
- `chrome://inspect` works (WebView debugging is enabled) for debugging Harmonium on the device.
- After a Harmonium engine change: reload/clear cache on the remote. After an integration `.py` change: restart HA.
- Don't put the long-lived token in logs, commits or chat. The native side doesn't need one any more (ESPHome); the optional token only goes to Harmonium.
- Android 8.1 can't resolve `.local` hostnames: the HA URL must be an IP address.
- No Android SDK in the cloud sandbox (dl.google.com is blocked). Kotlin was type-checked there with kotlinc against Robolectric's `android-all:8.1.0` jar and stubbed androidx classes, but not built with Gradle. Build and lint in Android Studio.

## Licensing (matters if borrowing code)
- Harmonium: no license chosen, all rights reserved. Load it from the user's own HA; don't bundle it in the APK. Author's roadmap mentions voice via the IME trick and a minimal APK shell, so coordinate if it gets serious.
- Kiosk Satellite: CC BY-NC-ND 4.0. Read for ideas only; don't copy code.
- Ava (brownard/Ava, an ESPHome-protocol voice satellite in Kotlin, and knoop7/Ava): check the license before reusing anything.
- appletv-siri-voice: Apache-2.0 (no longer used).
- ESPHome native API: `EspServer` is written from api.proto's message ids and field numbers (protocol facts); no ESPHome or aioesphomeapi code is copied.

## Roadmap
1. Add the remote to HA as an ESPHome device; verify push-to-talk end to end (F3 held).
2. Charging screen, battery readout and unplug wake: verify on the cradle.
3. Proximity and lift-wake: with 0.4's cradle-only keep-awake, check the screen times out off the cradle and lifting wakes it; read the logs for proximity events. Measure battery over an evening.
4. Verify the default key rules against Harmonium's keymap; then retire Key Mapper's Fully-scoped rules.
5. Maybe: MediaSession remote-volume so volume works with the screen off (see "Why the first press gets lost").
6. Done in 0.5: the app can be the HOME app (asks once). Verify it replaces Fully Kiosk / the stock launcher after a reboot.
7. Maybe: announce support (HA speaks on the remote), a keep-awake switch, Noise encryption.

## Dev workflow
```bash
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
adb connect <remote-ip>:5555                      # after `adb tcpip 5555` over USB once per reboot
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.harmoniumhost/.MainActivity --es room great_room --es start_page great_room
adb shell am start -n com.example.harmoniumhost/.SettingsActivity   # or swipe down / hold at the top edge
adb shell am dumpheap com.example.harmoniumhost /data/local/tmp/hh.hprof && adb pull /data/local/tmp/hh.hprof   # heap dump, open in Android Studio
adb logcat -s HarmoniumHost
```
