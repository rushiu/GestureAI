# Gesture Glasses

Runs on the MicroLumin M6 glasses only. The glasses camera watches the hands of the person
in front of you and shows the meaning in big green text, and speaks it (see Voice below).
Everything runs on the glasses: no phone, no network, and no video is saved or sent.

## What the wearer sees

1. **Welcome (4 s):** "Gesture Glasses — Signs from the person in front of you appear here".
   Shown every time the app comes to the front, e.g. when someone new puts the glasses on.
2. **Waiting:** "Ask them to wave hello". Nothing is read yet.
3. **Reading:** after the signer holds up an open hand (the wave), each sign held still for about
   0.6 s appears large in green and is spoken. The last four stay along the bottom.
4. **Back to waiting** after 30 s with no hands in view, or when a new wearer starts.

The amber line at the top always says what the camera is doing ("Waiting for a wave · nothing is
read until then" / "● Reading their signs on this device · no video saved").

**Consent:** the wave is how the person signing agrees to be read. A trained model must keep a
sign named `hello` or `start` (an open palm) for this, or the app waits for a wave forever.

## Built-in signs

Thumb_Up = Yes, Thumb_Down = No, Open_Palm = Hello (also the wave that starts reading),
Closed_Fist = Stop, Pointing_Up = Wait, Victory = Peace, ILoveYou = I love you.

- Change what each one says: `phrases` at the top of `app/src/main/java/com/worn/gestureglasses/MainActivity.kt`
- Your own signs: train with `train_signs.ipynb` in Google Colab (keep a `hello` folder), then put
  the downloaded `gesture_recognizer.task` in `app/src/main/assets/` (replace the old one) and press Run again.
- How sure it must be: a sign shows when 3 of the last 5 frames agree (`Smoother` in `MainActivity.kt`).

## Opening the app

The glasses' menu (App Center) only lists MicroLumin's own apps, so Gesture Glasses isn't there.
Instead it keeps itself on screen:

- It comes back to the front when the glasses wake, about 2 s after another app (like the
  built-in camera) closes, and after 20 s in the background.
- After the glasses restart, open it once from a computer: press Run in Android Studio, or
  ```bash
  adb shell am start -n com.worn.gestureglasses/.MainActivity
  ```
  (The glasses don't let sideloaded apps start by themselves at boot.)

To use a built-in app for longer, turn the auto-return off until the app is next opened:
`adb shell am stopservice com.worn.gestureglasses/.WakeWatcher`.

## Installing from more than one laptop

Each laptop signs debug builds with its own key, and Android won't install over an app signed
with a different one (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Pull `main` first, then
`adb uninstall com.worn.gestureglasses` before installing from a different laptop.

## Status line and logs

- The dim line at the bottom-left is for the team: camera, model, frames per second, voice, and
  `phone link`. The phone link (Bluetooth channel 205, `PhoneLink.kt`) is an unfinished experiment:
  the glasses refuse that channel, so it reads `phone link refused` and does nothing else.
- Logs: `adb logcat -s Gesture`

## Voice (spoken gestures)

The glasses don't come with a text-to-speech engine (the voice assistant uses its own, which other
apps can't use), so out of the box the status line shows `no voice: no speech engine`. To hear
gestures spoken, install eSpeak NG on the glasses once:

1. Download the eSpeak NG APK from F-Droid: https://f-droid.org/packages/com.reecedunn.espeak/
2. With the glasses plugged in over USB:
   ```bash
   adb install com.reecedunn.espeak_22.apk
   adb shell settings put secure tts_default_synth com.reecedunn.espeak
   adb shell am start -n com.reecedunn.espeak/.eSpeakActivity   # open it once, or other apps can't use it
   ```
3. Restart Gesture Glasses. The status line should say `voice on`.

This setup lives on the glasses, not in the app: redo it after a factory reset, a system update
that wipes apps, or on a new pair of glasses.
