# Gesture Glasses

Runs on the MicroLumin M6 glasses only. The glasses camera watches the hands of the person
in front of you and shows the meaning in big green text (and speaks it, if the glasses have a voice).

- Change what each gesture says: `phrases` at the top of `app/src/main/java/com/worn/gestureglasses/MainActivity.kt`
- Your own signs: train with `train_signs.ipynb` in Google Colab, then put the downloaded
  `gesture_recognizer.task` in `app/src/main/assets/` (replace the old one) and press Run again.
- Status line (bottom-left of the display): camera, model, frames per second, voice.
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

Built-in gestures: Thumb_Up=Yes, Thumb_Down=No, Open_Palm=Hello, Closed_Fist=Stop,
Pointing_Up=Wait, Victory=Peace, ILoveYou=I love you.
