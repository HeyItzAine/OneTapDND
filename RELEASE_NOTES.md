Download **OneTapDND.apk** and install it over your existing app.

- Choose silent, vibrate, or sound for each quiet place using the new three-position selector.
- Turn off **Change ringer mode** to keep the phone's current ringer setting. Sound and vibration follow your DND exceptions.
- **Keep media muted** is now a separate switch.
- Ringer changes use the requested Android mode directly, check the result, and report rejected changes in the app and monitoring notification.
- Leaving or pausing restores the previous ringer mode when it still matches the app's applied setting. Manual changes are preserved on exit.
- Overlapping places combine the quietest ringer choice with any active media-mute request.
- Existing saved places retain their settings.
- Release builds require the release signing configuration and no longer fall back to a debug key.

This replaces the withdrawn v1.4.3 release. Its internal version code is 11, so it can update the withdrawn v1.4.3 and v1.4.4 builds as well as earlier releases from v1.1 onward. The APK uses the existing release signing key and retains saved places and settings.

Validation: 36 unit tests passed, debug and release builds completed, and lint passed with warnings. Phone sound and vibration behavior still needs device validation.
