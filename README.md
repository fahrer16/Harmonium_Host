# Harmonium Host

Native Android shell for the Sanytron Astrion HA100 remote. It runs
[Harmonium](https://github.com/skavan/harmonium) full screen (loaded from your own Home Assistant)
and adds what a kiosk browser can't do:

- **A voice assistant per remote.** The remote shows up in Home Assistant as an ESPHome device with
  an Assist satellite, so you give it an area and a pipeline like any voice satellite. Hold the
  mic button (F3), speak, release. The reply plays on the remote. Works with plain-http HA.
- **First press always works.** While an activity is running, the screen stays on, dimmed, so no
  key press is spent on waking it.
- **Charging feedback.** A charging screen after the remote lands on the cradle, and a small
  battery readout at the top. Battery and charging are also HA entities.
- **Key translation** that replaces Key Mapper (Back, volume, mute, menu, long presses).
- **Home Assistant controls and diagnostics**, like a kiosk app: screen on/off, screensaver,
  brightness, volume, reload, clear cache, restart, a screenshot camera, and device diagnostics.
- **All settings on the remote**, in a settings screen styled like Harmonium. Nothing is compiled in.

## Install

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.harmoniumhost/.MainActivity
```

Check you're running this build: the settings title shows the version (0.3), and
`adb logcat -s HarmoniumHost` prints `Harmonium Host 0.3 starting`.

## Settings

Open settings in any of these ways:
- **Swipe down from the top edge of the screen** (like Android's own pull-down).
- Open **Harmonium settings** from the Android app drawer.
- `adb shell am start -n com.example.harmoniumhost/.SettingsActivity`

The settings screen also has buttons for Android's own settings and Wi-Fi settings.

The minimum setup:
1. **Home Assistant URL**, e.g. `http://192.168.1.10:8123`. Use the IP address: Android 8.1
   can't resolve `.local` names.
2. **Remote profile**: `astrion` or `astrion2`. Harmonium needs this to treat the remote as a
   hardware remote (D-pad passthrough to TVs, no on-screen D-pad). It's sent on every load.
3. **Start page**: a Harmonium page id (e.g. `great_room`), or blank for Harmonium's home.
4. **Room id**: the `<room>` in `select.harmonium_<room>_activity`, used for keep-awake.

Harmonium signs in with its own pairing (approve the remote in the Studio). A token field is
there if you'd rather hand it one.

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
| Buttons | Bring to front, Reload page, Clear cache, Restart app, Take screenshot |
| Camera | Screenshot (a fresh one each time HA asks, or after "Take screenshot") |
| Sensors | Battery, Charging, Ambient light (lux, only while the screen is on), Last interaction, Activity running |
| Diagnostics | Android version, App version, App uptime, Connected, CPU usage, Current page, Device name, Foreground app, Internal storage free/total, IPv4 address, Last seen, Network uptime, RAM available/total, Wi-Fi signal |

Some controls need a one-time permission, granted from the remote's Settings → Permissions:
- **Screen off**: device admin ("force lock"). Without it, turning the screen off shows a black
  screensaver at minimum brightness instead.
- **Modify system settings**: brightness, adaptive brightness and Android's screen timeout.
- **Usage access**: the Foreground app diagnostic. Without it, it only says whether it's this app.

CPU usage is the whole device when Android allows reading it; Android 8 usually doesn't, and it
then reports this app's share. Diagnostics refresh every minute; controls report back right away.

The weather screensaver reads an HA weather entity (e.g. `weather.home`) set in the remote's
settings; HA forwards its condition and temperature over the ESPHome connection.

## Battery

Keeping the screen on costs battery: the backlight at minimum plus a CPU that can't suspend. The
defaults keep the cost down:
- The screen is kept on only while an activity is running, and dims after 15 s.
- It is released after 20 minutes without a press. Then Android's own screen timeout applies,
  which you can also set in settings.
- On the cradle there is no limit, because battery isn't an issue there.
- The high-performance Wi-Fi lock is held only while the screen is on.
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
