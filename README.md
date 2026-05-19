# One Tap DND

Google removed the one-tap Do Not Disturb toggle from Quick Settings in Android 15, replacing it with a "Modes" panel that takes two taps. This app brings back the original behavior — a single tap to turn DND on or off, right from your Quick Settings.

## How it works

The app adds a custom Quick Settings tile called "Do Not Disturb". Tap it once to enable DND, tap it again to disable it. That's it.

## Setup

1. Install the app
2. Open it and tap **Grant DND Access** — this takes you to a system settings page where you toggle permission for the app
3. Add the tile to your Quick Settings panel (on Android 13+, the app can do this for you with a button; on older versions, swipe down, tap the pencil/edit icon, and drag the "Do Not Disturb" tile in)
4. Done. You can close the app — the tile works independently

## Permissions

This app requires exactly **one** permission:

- **Do Not Disturb access** (`ACCESS_NOTIFICATION_POLICY`) — needed to read and toggle your DND state

That's the only permission. The app:
- Has no internet access
- Collects no data
- Stores nothing on your device
- Contains no ads or tracking
- Has no third-party dependencies beyond standard Android libraries

## Install

### From GitHub Releases

Download the latest APK from the [Releases](../../releases) page and sideload it onto your device.

### Build from source

```
git clone https://github.com/YOUR_USERNAME/OneTapDND.git
cd OneTapDND
./gradlew assembleRelease
```

The APK will be at `app/build/outputs/apk/release/`.

## Compatibility

- **Minimum:** Android 7.0 (API 24)
- **Target:** Android 15 (API 36)
- Tested on Pixel devices running Android 14 and 15

## Why does this exist?

Starting with Android 15 QPR2, Google replaced the DND quick settings tile with a "Modes" tile. The Modes tile opens a panel where you then select Do Not Disturb — turning a one-tap action into a two-tap action. For people who just want to quickly silence their phone, this was a step backwards.

Google has acknowledged this and is working on bringing back a dedicated DND tile in a future Android 16 update. Until that rolls out to everyone, this app fills the gap.

## License

This project is licensed under the GNU General Public License v3.0. See the [LICENSE](LICENSE) file for details.
