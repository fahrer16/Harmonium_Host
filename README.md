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
- **All settings on the remote**, in a settings screen. Nothing is compiled in.

## Install

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.harmoniumhost/.MainActivity
```

Check you're running this build: the settings title shows the version (0.2), and
`adb logcat -s HarmoniumHost` prints `Harmonium Host 0.2 starting`.

## Settings

Open settings in any of these ways:
- Hold the top centre edge of the screen (where the battery % shows) for 3 seconds.
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

The device also has **Battery** and **Charging** entities. HA's "Finished speaking detection"
setting on the device decides how long a pause ends the sentence. Releasing the button always
ends it.

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
