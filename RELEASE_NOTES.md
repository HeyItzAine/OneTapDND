Download **OneTapDND.apk** and install it over your existing release. The existing signing key is retained, so saved places and settings are preserved.

- Fix automatic DND staying active after a quiet period ends when its boundary broadcast is delayed. Keep an expiry monitor running during active timed periods, including periods without media muting.
- Add a service-lifetime alarm callback alongside the existing broadcast alarm.
- Finish pending ringer restoration before stopping the monitor, rather than waiting solely for a background worker. Restore app-owned media volume and ringer settings after expiry.
- Release the current automatic rule before scheduling the next quiet period, so a scheduling failure cannot block deactivation.
- Preserve separately enabled manual DND when an automatic period ends. Other active quiet periods and Hybrid location triggers continue to apply.

An ongoing notification appears during active timed monitoring. Allow **Alarms & reminders** for precise scheduled changes, especially while the phone is asleep; Android can still defer alarms under device power restrictions.

Validation: all 54 unit tests and three emulator regression tests passed. The old expiry behavior was reproduced with the boundary receiver disabled. Regression tests verify DND-only expiry, media and ringer restoration, and preservation of manual DND. Debug lint passed with no errors. The release workflow runs unit tests, release lint, optimized APK constructor checks, and signing-certificate verification before publishing.

Version 1.5.2 uses internal version code 16.
