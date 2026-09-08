Download **OneTapDND.apk** below and open it on your Android phone to install.

- Launcher alias repair now waits until the app leaves the foreground, avoiding package restarts during app startup on affected devices.
- Media-zero enforcement now stops safely if Android rejects foreground-service startup or its settings observer.

**Upgrading from v1.1, v1.1.1, v1.2, v1.3, or v1.4:** install this APK over the existing app. It uses the same signing key and keeps your settings.

**Upgrading from v1.0:** uninstall v1.0 first because its signing key differs, then grant DND access and add the tile again.

- Choose DND only, DND plus silent ringer, or DND plus silent ringer with media held at zero for each saved place. Alarms and call audio are left alone.
- Overlapping places use the strongest selected mode. Leaving or pausing restores the app-owned ringer and media settings to their saved values when they have not been replaced by a newer change.
- Pause all quiet-place monitoring for one hour, three hours, or a custom duration. The ongoing silent notification provides all three actions.
- The Quick Settings tile pauses monitoring for one hour when used inside a saved place and resumes immediately when monitoring is paused. Away from a place, it remains a manual DND toggle.
- The main screen now keeps setup details collapsed. Saved-place rows and the full-screen place editor use shorter labels and put manual coordinates, screenshot reading, and map preview under Advanced location.
- Search, OCR, and pasted multiline addresses fill coordinates without overwriting labels such as Home or Work.
- The launcher icons now use centered adaptive artwork, full-mask inverse backgrounds, monochrome layers on Android 13+, and circular legacy fallbacks without a white outer plate.
- Icon selection uses two aligned rows and applies the chosen launcher alias after leaving the app.
- Android 13+ can show quiet-place status and pause controls through notification permission. Monitoring continues if notification permission is denied.
- Distance checks now reschedule themselves from the nearest-place travel time: 1 km maps to 5 minutes, 12 km to 1 hour, and delays stop growing at 24 hours. Geofences remain active so the app does not poll continuously.

Always preview an extracted location before saving it. Quiet places require Google Play services, precise location, and background location access. Android can take a few minutes to detect an arrival or departure. Reopen the app after force-stopping it.
