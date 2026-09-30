# Harmonium Host

Native Android shell for the Sanytron Astrion HA100 remote. It runs
[Harmonium](https://github.com/skavan/harmonium) full screen (loaded from your own Home Assistant)
and adds what a kiosk browser can't do:

- **A voice assistant per remote.** The remote shows up in Home Assistant as an ESPHome device with
  an Assist satellite, so you give it an area and a pipeline like any voice satellite. Hold the
  mic button (F3), speak, release. The reply plays on the remote. Works with plain-http HA.
- **The screen is awake when you pick it up.** Off the cradle the screen sleeps and the remote's
  firmware wakes it when lifted; on the cradle it stays on, dimmed, with a clock screensaver.
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

Check you're running this build: the settings title shows the version (0.6), and
`adb logcat -s HarmoniumHost` prints `Harmonium Host 0.6 starting`.

## Settings

**Pull down from the top edge** (like Android's notification shade) for the quick panel: Wi-Fi
and Home Assistant status, battery, brightness and volume sliders, and buttons for Reload, Stay on
(keep the screen on everywhere), Clock (screensaver) and **Settings** (everything below). Touching
and holding near the top edge for a second opens it too. Swipe it back up, tap below it, or press
Back to close it; it closes by itself after 30 s.

If the stock Astrion app is running it can take the pull for its own panel; make Harmonium Host
the home screen (it asks once; also Settings → Permissions → Home screen) and restart the remote.

Other ways into Settings:
- Press **Open settings on the remote** on the device in Home Assistant.
- Open **Harmonium settings** from the Android app drawer.
- `adb shell am start -n com.example.harmoniumhost/.SettingsActivity`

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
- Off the cradle, Android's own screen timeout turns it off (set it in settings), and the
  remote's firmware turns it back on when you pick the remote up.
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
