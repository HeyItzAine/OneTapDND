Download **OneTapDND.apk** and install it over your existing app. No uninstall is needed for installations signed with the existing release key. Saved places and settings are retained.

- Fix Silent when DND reports a silent ringer but the phone's internal mode is still Sound or Vibrate. Apply an explicit ringer transition when Silent is selected.
- Remember the requested mode so repeated location checks do not toggle the ringer again.
- Preserve place DND through the ringer transition and restore the starting ringer mode on exit.
- Shrink the Sound / Vibrate / Silent selector to a 216 x 64 dp pill with 48 dp selection circles. Each choice keeps a 72 x 64 dp touch target.

To test: start in Sound, Vibrate, or Silent; enter a quiet place; then move beyond its radius plus the reported location uncertainty (at least 25 m). Use **Check location now** to check the boundary directly. Background geofence detection can take a few minutes. Leaving the last place ends its DND; separately enabled manual DND remains on.

Android limitation: restoring an originally Silent ringer can also enable system DND. The place rule is released, but system DND can remain on. Restoring Sound or Vibrate turns it off unless another DND rule is active.

Validation: 44 unit tests passed. A physical-phone test checked the internal Silent state, repeated checks, mode switching, and ringer restoration on simulated place exit. Three UI tests passed on the same phone, including selector dimensions and all three choices. Debug lint passed with warnings. Physical walking tests are still needed to check location detection.

Version 1.4.5 uses internal version code 13. The release workflow verifies the APK against the existing signing certificate before publishing.
