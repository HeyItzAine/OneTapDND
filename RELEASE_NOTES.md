Download **OneTapDND.apk** below and open it on your Android phone to install.

**Upgrading from v1.1:** install this APK over the existing app. It uses the same signing key and keeps your settings.

**Upgrading from v1.0:** uninstall v1.0 first because its signing key differs, then grant DND access and add the tile again.

- Fixes the startup crash caused by release optimization removing WorkManager's database constructor.
- Preserves the background place worker's constructor in optimized releases.
- The Quick Settings tile uses a saved DND rule and works without opening the app first.
- Add quiet places by address search, current location, or coordinates, with a radius from 100 to 10,000 meters.
- Choose DND or total silence on arrival. Total silence also mutes media and alarms.
- Leaving a place ends its mode. Overlapping places remain active until you leave them too.
- Turning the tile off pauses active places until you leave. Other Android modes remain under their own controls.

Quiet places require Google Play services, precise location, and background location access. Android can take a few minutes to detect an arrival or departure. Reopen the app after force-stopping it.
