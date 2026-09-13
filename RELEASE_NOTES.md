Download **OneTapDND.apk** and install it over your existing app. No uninstall is needed for installations signed with the existing release key. Saved places and settings are retained.

- Capture the original ringer mode before DND changes it, and restore it after Android finishes the DND transition.
- Save pending ringer restoration and retry failed restores.
- Update active places when a fresh foreground location confirms entry or exit. Add **Check location now** for testing.
- Schedule backup checks from the place boundary, not its centre, and retry unavailable location sooner while inside a place.
- Reject stale location updates that could reactivate a place after a newer exit.
- Release duplicate place DND rules on exit and fix rapid media-mute service start/stop handling.
- Explain when manual DND is enabled separately and provide a button to turn it off.

To test: start in Sound, Vibrate, or Silent; enter a quiet place; then move beyond its radius plus the reported location uncertainty (at least 25 m). Use **Check location now** to check the boundary directly. Background geofence detection can take a few minutes. Leaving the last place ends its DND; separately enabled manual DND remains on.

Validation: 42 unit tests and 15 Android emulator tests passed, including all nine original/selected ringer-mode combinations, repeated checks, location exits, pause, disabled places, media restoration, and manual choices. Debug lint passed with warnings. Physical-device walking tests are still needed.

Version 1.4.4 uses internal version code 12, higher than both the current 1.4.3 release and the withdrawn older builds. The release workflow verifies the APK against the existing signing certificate before publishing.
