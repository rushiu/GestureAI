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

## ASL signs (alongside the gestures)

After the signer waves hello (open palm), the app reads both at once:

- **Gestures** (the list at the bottom): held hand shapes, such as thumbs up = Yes.
- **ASL signs**: a pretrained model recognizes 250 everyday ASL signs that involve movement, such as
  "thank you", "please", "bye", "mom" and "drink". No training needed. The model is the 1st-place entry from
  Google's Kaggle "ASL Signs" competition ([sign/kaggle-asl-signs-1st-place](https://huggingface.co/sign/kaggle-asl-signs-1st-place), MIT);
  the full word list is in `app/src/main/assets/sign_to_prediction_index_map.json` after the first build.

How they share the work:

- One hand tracker for both: the gesture model already finds the hand's 21 points, and the ASL reader uses
  those, adding body and face points (`LandmarkExtractor.kt`).
- A gesture only counts while the hand is held still; a moving hand is read as an ASL sign. Otherwise
  flat-hand signs like "thank you" would also show as Open_Palm ("Hello").
- A sign ends when the hand stops moving, is lowered, or after 2 seconds (`SignSegmenter.kt`).
- After the wave, hands are only looked for above mid-torso, so a hand resting at the hip is ignored.

Notes:

- The models (about 20 MB) download on the first build and aren't committed.
- ASL signs need the body and face as well as the hand, so the frame rate drops to about 3 fps while
  someone signs (about 5-7 fps otherwise).
- Best results: signer facing the wearer, about 1 m away, face and signing hand in view. One-handed signs
  at the face or chest work best (hello, bye, mom, dad, please, drink, think). Single signs, not sentences.
- Change what a sign shows or says: the ASL entries in `phrases` in `MainActivity.kt`.
- Logs show each guess with its confidence: `adb logcat -s Gesture`. `MIN_SIGN_PROBABILITY` sets the cutoff.

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
