Download **OneTapDND.apk** and install it over your existing app. Installations signed with the existing release key keep saved places and settings.

- Add a three-position selector for Time based, Location based, and Hybrid automation. Tap or drag to select a mode.
- Set a daily start time and a duration, or choose an end time. Quiet time can cross midnight.
- Hybrid enables automatic DND when either the daily time window or a saved place is active. It ends when both triggers are inactive. Separately enabled manual DND remains independent.
- Add optional audio profiles for the phone speaker and wired, USB, or Bluetooth headphones. Each profile controls the ringer and media muting during automatic DND.
- Move permissions below the main controls, followed by app icon settings.
- Include the DND state synchronization and quiet-place flicker fixes since the previous release.

Allow **Alarms & reminders** for precise timing. Without it, Android may delay scheduled changes. Location and Hybrid modes still need precise and background location access. Existing installations default to Location based mode; daily quiet time and connection profiles are opt-in.

Validation: debug APK built successfully and all 49 unit tests passed, including daily start/end boundaries and a window crossing midnight. Physical-device checks of headphone switching and background timing are still needed. The release workflow runs unit tests, release lint, optimized APK constructor checks, and signing-certificate verification before publishing.

Version 1.5.0 uses internal version code 14.
