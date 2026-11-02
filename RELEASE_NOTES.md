Download **OneTapDND.apk** and install it over your existing release. The existing signing key is retained, so saved places and settings are preserved.

- Add multiple daily quiet periods, each with its own on/off switch. Edit a period by tapping its clock row, or delete it from its menu.
- Replace the old time controls with a minimal clock list and an analog clock editor. Choose Start and End times, use keyboard input, or apply a 20-, 30-, or 60-minute duration shortcut.
- Support overlapping periods and windows crossing midnight. Automatic DND stays active until every active period ends; Hybrid also stays active while a saved place is active.
- Preserve existing single-period schedules when upgrading. Deleting the last period keeps the schedule empty, including after reopening the app.
- Keep Save and Cancel reachable on smaller screens.

Allow **Alarms & reminders** for precise scheduled changes. Without access, Android may delay them. Separately enabled manual DND remains independent.

Validation: all 54 unit tests and two emulator tests passed. The emulator tests cover adding, disabling, deleting, migration, persistence, and enabling an empty schedule. The clock list and editor were visually checked on the emulator. Debug lint passed with no errors. Physical-device timing and headphone checks remain outstanding. The release workflow runs unit tests, release lint, optimized APK constructor checks, and signing-certificate verification before publishing.

Version 1.5.1 uses internal version code 15.
