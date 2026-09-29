# Harmonium Host

Native Android shell for the Sanytron Astrion HA100 remote. It runs
[Harmonium](https://github.com/skavan/harmonium) full screen (loaded from your own Home Assistant)
and adds what a kiosk browser can't do:

- **Push-to-talk voice.** Hold the mic button (F3), speak, release. Audio goes to a Home Assistant
  Assist pipeline; the spoken reply plays on the remote.
- **First press always works.** While an activity is running, the screen stays on, dimmed, so no
  key press is spent on waking it. The proximity sensor wakes or brightens the screen as a hand
  comes near.
- **Charging feedback.** A charging screen for a few seconds after the remote lands on the
  cradle, plus a small battery readout at the top of the screen.
- **Key translation** that replaces Key Mapper (Back, volume, mute, menu, long presses).
- **All settings on the remote**, in a settings screen. Nothing is compiled in.

## Install

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.harmoniumhost/.MainActivity
```

On first launch the settings screen opens. Allow the microphone when asked.

## Settings

Open settings in any of these ways:
- Hold the battery readout at the top of the screen for 3 seconds.
- Open **Harmonium settings** from the Android app drawer.
- Run `adb shell am start -n com.example.harmoniumhost/.SettingsActivity`.

The minimum setup:
1. **Home Assistant URL**, e.g. `http://192.168.1.10:8123`. Use the IP address: Android 8.1
   can't resolve `.local` names.
2. **Room id**: the `<room>` in `select.harmonium_<room>_activity`.
3. **Token**: leave it blank and pair the remote in Harmonium (the Studio approves it). The app
   picks up Harmonium's token automatically. Or paste a long-lived token over adb:
   `adb shell am start -n com.example.harmoniumhost/.MainActivity --es token "<LLAT>"`
4. Press **Test connection**. It checks the token and the activity entity, lists your Assist
   pipelines, and lets you pick one.

## What Home Assistant needs

- **An Assist pipeline with speech-to-text** (and preferably text-to-speech). No extra device
  or entity is needed for voice: the app talks to the pipeline directly over HA's websocket.
  - Local: install the **Whisper** (speech-to-text) and **Piper** (text-to-speech) add-ons, or
    **Speech-to-Phrase** for fast, command-only recognition on weaker HA hardware. Add them
    through the Wyoming integration, then create or edit a pipeline in
    *Settings → Voice assistants* to use them.
  - Or Home Assistant Cloud's speech services.
  - Either make that pipeline the preferred one, or pick it in the app's settings.
  - Name the area in commands ("turn on the great room lights"). The remote isn't a device in
    an area yet, so "turn on the lights" has no room to infer.
- **The Harmonium integration**, which already provides `select.harmonium_<room>_activity`. Its
  state is `off` when nothing is running. The keep-awake feature follows it.
- The token's user needs no admin rights.

Siri (optional, off by default): enable it in settings to send utterances to
[appletv-siri-voice](https://github.com/marcusadolfsson/appletv-siri-voice) whenever an HA
`input_boolean` is on. See `ha/packages/remote_voice.yaml`.

## Battery

Keeping the screen on costs battery: the backlight at minimum plus a CPU that can't suspend.
Those are the costs of never losing a key press. The defaults keep the cost down:
- The screen is kept on only while an activity is running, and dims after 15 s.
- It is released after 20 minutes without a press. Then Android's own screen timeout applies,
  which you can also set in settings.
- On the cradle there is no limit, because battery isn't an issue there.
- The HA websocket and the high-performance Wi-Fi lock are held only while the screen is on.
- Proximity is an on-change sensor, so it costs almost nothing.

To measure, note the battery % at the top of the screen over a normal evening, or run:

```bash
adb shell dumpsys batterystats --reset     # then use the remote normally for a few hours
adb shell dumpsys batterystats | grep -iE "screen on|Estimated power|harmoniumhost"
```

If it drains too fast, shorten *Stop keeping it on after* or *Dim after*.

## Debugging

```bash
adb logcat -s HarmoniumHost
```

This logs every key (keycode and scancode), proximity near/far events, the activity state,
keep-awake on/off, and the HA link going up and down. Harmonium itself can be inspected in
`chrome://inspect`.
