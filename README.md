# Harmonium Host

A native Android app for the **Sanytron Astrion HA100** Home Assistant remote. It runs
[Harmonium](https://github.com/skavan/harmonium) full screen and adds the parts a browser can't
do: a push-to-talk voice assistant, a screen that's awake when you pick the remote up, charging
feedback, key mapping, and remote control and diagnostics from Home Assistant.

> **Harmonium is required.** This app is only a host for
> [Harmonium](https://github.com/skavan/harmonium), the Home Assistant remote-control frontend by
> [skavan](https://github.com/skavan). It loads Harmonium from your own Home Assistant and does
> nothing useful without it. Install and set up Harmonium first, following its own instructions.
> All credit for the remote's interface goes to Harmonium; this project isn't affiliated with it.

## Why it exists

Harmonium on the Astrion used to run in **Fully Kiosk Browser**, with **Key Mapper** translating
the remote's buttons. That left gaps:

- **The mic button didn't work.** Browsers only allow the microphone on HTTPS pages, and Home
  Assistant here is plain `http://`.
- **The first press was lost.** A sleeping screen spent the first key press on waking up, and
  Harmonium then had to reconnect.
- **No charging feedback** on the cradle, and two separate apps to set up and keep in step.

Harmonium Host replaces both apps with one:

| | Fully Kiosk + Key Mapper | Harmonium Host |
|---|---|---|
| Voice | Mic blocked on plain http | Hold the mic button: Home Assistant Assist, per remote, with its area; the reply plays on the remote |
| Waking | First press wakes the screen | Lift-to-wake, and a dimmed screen with a clock on the cradle |
| Buttons | Separate Key Mapper rules | Built-in key rules, editable on the remote |
| Charging | Nothing | Charging screen and battery readout |
| Home Assistant | Kiosk controls (Fully's API) | A native ESPHome device: controls, settings, screenshot, diagnostics, voice satellite |
| Setup | Two apps | One app; a pull-down panel and settings screen styled like Harmonium |

## Requirements

- A Sanytron Astrion HA100. It may run on other Android 8.1+ devices, but that's untested.
- Home Assistant on your network, reached by **IP address** (Android 8.1 can't resolve `.local`
  names). Plain `http://` is fine.
- [Harmonium](https://github.com/skavan/harmonium) installed in Home Assistant and working in a
  browser.
- For voice: an Assist pipeline with speech-to-text (e.g. the Whisper and Piper add-ons).
- A computer with [adb](https://developer.android.com/tools/releases/platform-tools) to install
  the app.

## Install

1. **Get the APK.** Download `harmonium-host-<version>.apk` from the
   [latest release](https://github.com/fahrer16/Harmonium_Host/releases/latest).
2. **Turn on USB debugging on the remote.** In Android's Settings → About, tap *Build number*
   seven times, then turn on Settings → Developer options → *USB debugging*. Connect the remote
   by USB and accept the prompt.
3. **Install it:**
   ```bash
   adb install harmonium-host-1.0.0.apk
   ```
   If you installed a development build before, uninstall it first; it's signed differently.
4. **Open Harmonium Host** on the remote and allow the microphone. Settings opens on the first
   start. Fill in at least:
   - **Home Assistant URL**, e.g. `http://192.168.1.10:8123`
   - **Remote profile**: `astrion` (or `astrion2`)
   - **Start page** (optional) and **Room id**, as used in Harmonium

   Then **Save**. Harmonium loads; sign it in with its own pairing, as in a browser.
5. **Answer the one-time questions** that follow:
   - **home screen**: choose Harmonium Host, so it starts after a reboot and the stock app stays out of the way
   - **Usage access**
   - **screen capture**: tick *Don't show again*
6. **Add the remote to Home Assistant.** In Settings → Devices & services, accept the discovered
   *Harmonium Remote …* (ESPHome) device, or add ESPHome with the remote's IP and port 6053.
   On the device page:
   - set its **area** and its **Assistant** pipeline
   - under *Configure*, turn on **Allow the device to perform Home Assistant actions** (for the entity dropdowns)
7. **Retire the old apps.** Remove or disable Key Mapper's rules for the remote's keys; its
   accessibility service can otherwise swallow keys before this app sees them. Fully Kiosk is no
   longer needed.
8. **Optional permissions**, from the pull-down panel → Settings → Permissions:
   - **Screen off**, so HA can turn the screen off
   - **Modify system settings**, for brightness and the screen timeout

Restart the remote once so it comes up in Harmonium Host.

**Updating:** install the newer APK over the old one (`adb install -r …`). Settings are kept.

## Settings

**Pull down from the top edge** (like Android's notification shade) for the quick panel: Wi-Fi
and Home Assistant status, battery, brightness and volume sliders, and buttons for Reload, Stay on
(keep the screen on everywhere), Clock (screensaver) and **Settings** (everything below). Touching
and holding near the top edge for a second opens it too. Swipe it back up, tap below it, or press
Back to close it; it closes by itself after 30 s.

If the stock Astrion app is running it can take the pull for its own panel; make Harmonium Host
the home screen (it asks once; also Settings → Permissions → Home screen) and restart the remote.
Holding a finger near the top edge for a second opens the panel either way.

Other ways into Settings:
- Press **Open settings on the remote** on the device in Home Assistant.
- Open **Harmonium settings** from the Android app drawer.
- `adb shell am start -n io.github.fahrer16.harmoniumhost/com.example.harmoniumhost.SettingsActivity`

The settings screen also has buttons for Android's own settings and Wi-Fi settings.

The minimum setup:
1. **Home Assistant URL**, e.g. `http://192.168.1.10:8123`. Use the IP address: Android 8.1
   can't resolve `.local` names.
2. **Remote profile**: `astrion` or `astrion2`. Harmonium needs this to treat the remote as a
   hardware remote (D-pad passthrough to TVs, no on-screen D-pad). It's sent on every load.
3. **Start page**: a Harmonium page id (e.g. `great_room`), or blank for Harmonium's home.
4. **Room id**: the `<room>` in `select.harmonium_<room>_activity`, used for the activity entity.

After the first save the app asks, once each, for the Android grants it uses: to be the home
screen, Usage access (the foreground app) and screen capture (screenshots of any app). Settings →
Permissions shows each one and has a button for it.

Harmonium signs in with its own pairing (approve the remote in the Studio). A token field is
there if you'd rather hand it one.

Once the remote is in Home Assistant, most settings can also be changed there, on the device
page under **Configuration** (see below). Changes apply right away.

## Voice: add the remote to Home Assistant

The remote runs the ESPHome native API on port 6053 and announces itself on the network.

1. In HA go to **Settings → Devices & services**. Accept the discovered
   "Harmonium Remote …" device. If it isn't discovered, choose **Add integration → ESPHome**
   and enter the remote's IP (shown in the remote's settings) and port 6053. No encryption key
   is needed.
2. Open the new device. Set its **area**, so "turn on the lights" means that room. Choose its
   **Assistant** (the Assist pipeline). The pipeline needs speech-to-text: for a local setup,
   install the Whisper (or Speech-to-Phrase) and Piper add-ons and use them in a pipeline under
   *Settings → Voice assistants*.
3. Hold the mic button, speak, release.

HA's "Finished speaking detection" setting on the device decides how long a pause ends the
sentence. Releasing the button always ends it.

## What the device offers Home Assistant

| Kind | Entities |
|---|---|
| Controls | Screen (switch), Screensaver (switch), Screensaver mode (black / clock / weather), Adaptive brightness (switch), Screen brightness (%), Volume (%) |
| Buttons | Bring to front, Reload page, Clear cache, Restart app, Take screenshot, Open settings on the remote |
| Camera | Screenshot: taken only when you press "Take screenshot". It shows whatever is on screen once screen capture is allowed (otherwise only this app). HA's own refreshes re-send the last one, at most every 5 s. |
| Sensors | Battery, Charging, Activity running |
| Settings | Home Assistant URL, Harmonium page path, Remote profile, Start page, Activity entity, Idle activity states, Weather entity, Keep screen on, Dim after, Dimmed brightness, Keep-on limit, No keep-on limit on the cradle, Proximity wake, Screensaver when dimmed, Charging screen, Battery readout (on/off, position, size), Long press, Android screen timeout |
| Diagnostics | Android version, App version, App uptime, App memory, Connected, CPU usage, Current page, Device name, Foreground app, Internal storage free/total, IPv4 address, Network uptime, RAM available/total, Wi-Fi signal |

**Entity dropdowns.** Activity entity and Weather entity are dropdowns of the matching entities
in your HA (activity selects, weather entities). The ESPHome connection can't list HA's entities,
so on each connection the remote asks HA to fill them in through an action (its own
`esphome.<device>_entity_lists`). Turn on **Allow the device to perform Home Assistant actions**
once: Settings → Devices & services → ESPHome → this remote → Configure. Until then HA shows a
repair about it, and the dropdowns offer only the current value. The remote's Settings screen
uses the same lists.

The settings are the same values as the remote's Settings screen; a change from either side
applies right away (page settings reload Harmonium). Left out on purpose: the token (it would
show as a plain entity state), the device names (renaming the ESPHome device from HA would cut
HA's own connection), the mic button (learned on the remote) and the key rules (longer than
HA's 255-character text limit). A text setting cleared to blank goes back to its default.
If the remote's Settings screen is open while HA changes something, close it without saving,
or the screen's older values win.

Some controls need a one-time permission, granted from the remote's Settings → Permissions:
- **Screen off**: device admin ("force lock"). Without it, turning the screen off shows a black
  screensaver at minimum brightness instead.
- **Modify system settings**: brightness, adaptive brightness and Android's screen timeout.
- **Usage access**: the Foreground app diagnostic (the package in front, e.g.
  `com.google.android.youtube`). Without it, it only says whether it's this app.
- **Screenshots**: Android's screen-capture dialog. Tick "Don't show again", or it asks each time
  the app starts.

CPU usage is the whole device when Android allows reading it; Android 8 usually doesn't, and it
then reports this app's share. Diagnostics refresh every minute; controls report back right away.

The weather screensaver reads an HA weather entity (e.g. `weather.home`) set in the remote's
settings; HA forwards its condition and temperature over the ESPHome connection.

## Battery

Keeping the screen on costs battery: the backlight at minimum plus a CPU that can't suspend. So
by default the screen is kept on **only on the cradle**:
- Off the cradle, Android's own screen timeout turns it off (set it in settings), and it wakes
  when you pick the remote up. Watching for that keeps the processor awake while the screen is
  off, which costs some battery; turn off "Wake when picked up" to save it.
- On the cradle it stays on, dims after 15 s and shows the screensaver (clock by default).
- "While an activity is running" (0.3's default) keeps it on, dimmed, off the cradle too, for up
  to 20 minutes after the last press. It makes the very first press reliable if lift-wake
  misses, at a real battery cost. 0.4 moves remotes still on that old default to the cradle
  setting once.
- The Settings screen no longer holds the screen on while it's open.
- The high-performance Wi-Fi lock is held only while the screen is on.
- Screenshots are taken only when you press the button.
- Home Assistant pings the ESPHome connection about every 20 s. Each ping wakes the remote
  briefly. That's the same as any ESPHome voice satellite.

To measure it, watch the battery % (it's now an HA entity, so you can graph it), or run:

```bash
adb shell dumpsys batterystats --reset     # then use the remote normally for a few hours
adb shell dumpsys batterystats | grep -iE "screen on|Estimated power|harmoniumhost"
```

## Debugging

```bash
adb logcat -s HarmoniumHost
```

This logs the version at start, every key (keycode and scancode), screen on/off, proximity
near/far events, the activity state, keep-awake on/off, and ESPHome connections and voice
events. Harmonium itself can be inspected in `chrome://inspect`.

## Building from source

Open the project in Android Studio, or:

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n io.github.fahrer16.harmoniumhost/com.example.harmoniumhost.MainActivity
```

A debug build is signed with your own debug key, so it can't update a release install (or the
other way round) without uninstalling first.

## Publishing a release

GitHub Actions builds the app on every pull request. Pushing a version tag (`v1.2.3`) builds the
signed release APK and publishes it as a GitHub release with the notes in
`docs/releases/v1.2.3.md`.

The release is signed with a key kept in the repository's secrets. **Use the same key for every
release**, or installed copies can't update. Create it once:

```bash
keytool -genkeypair -v -keystore harmonium-release.jks -alias harmonium \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Harmonium Host"
base64 -i harmonium-release.jks          # macOS; on Linux: base64 -w0 harmonium-release.jks
```

`keytool` comes with Android Studio: on macOS it's in
`/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/`.

Then, under the repository's Settings → Secrets and variables → Actions, add:
- `RELEASE_KEYSTORE_BASE64`: the base64 text
- `RELEASE_KEYSTORE_PASSWORD`: the keystore password
- `RELEASE_KEY_ALIAS`: `harmonium`
- `RELEASE_KEY_PASSWORD`: the key password; `keytool` uses the keystore password unless you gave another

Keep `harmonium-release.jks` and its password somewhere safe, outside the repository.

To release, bump `versionCode` and `versionName` in `app/build.gradle.kts` and write
`docs/releases/vX.Y.Z.md`. Merge, then tag the merge commit:

```bash
git tag v1.2.3 && git push origin v1.2.3
```

## Credits

- [Harmonium](https://github.com/skavan/harmonium) by [skavan](https://github.com/skavan): the
  remote-control interface this app hosts. Harmonium is not bundled; it's loaded from your own
  Home Assistant.
- The Home Assistant link speaks the [ESPHome](https://esphome.io) native API, written from its
  published protocol definition.
