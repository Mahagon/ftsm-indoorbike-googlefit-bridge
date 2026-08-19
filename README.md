# FTMS Indoor Bike Google Fit Bridge

FTMS Bike Bridge is an Android app that records workouts from an FTMS-compatible indoor bike over Bluetooth Low Energy. Workouts are saved in the app, exported to Android Health Connect, and can then appear in Google Fit.

## Features

- Records duration, distance, speed, cycling cadence, and power.
- Keeps a local, paginated workout history with summary statistics and Health Connect sync status.
- Supports one-session duration or distance targets with live progress.
- Supports manual recording or automatic background monitoring and recording.
- Restores background monitoring after a phone restart.
- Continues recording with the screen off and tolerates a bike disconnect for up to five minutes.
- Writes completed stationary-cycling sessions and available metric samples to Health Connect.
- Follows the phone's light or dark appearance setting.
- Checks GitHub Releases for signed updates and verifies downloaded APKs before installation.

The app does not control resistance, estimate calories, or use the deprecated Google Fit API.

## Requirements

- An Android 14 or newer phone with Bluetooth Low Energy and Health Connect.
- An indoor bike exposing the Fitness Machine Service (`0x1826`) and Indoor Bike Data characteristic (`0x2AD2`).
- No other app, such as Zwift or Kinomap, connected to the bike at the same time.

Health Connect is built into Android 14 and newer. See the [Android Health Connect documentation](https://developer.android.com/health-and-fitness/health-connect/availability) for platform details.

## Install

1. Open the [latest GitHub release](https://github.com/Mahagon/ftsm-indoorbike-googlefit-bridge/releases/latest).
2. Download the `ftms-bridge-vX.Y.Z.apk` asset, not the source archives or `.sha256` file.
3. Open the APK on the phone and approve installation from the browser or file manager if Android asks.
4. Launch **FTMS Bike Bridge** and grant Nearby devices, notification, and Health Connect permissions when requested.

The Android package name is `dev.frakw.ftmsbridge`. Updates must be signed with the same release key as the installed version.

## Connect a bike

1. Wake the bike by pedaling.
2. Ensure other fitness apps are disconnected from it.
3. Tap **Scan** in FTMS Bike Bridge.
4. Select the bike and wait for the app to show **Ready**.

The selected bike is remembered for future reconnections.

## Record workouts

### Set a session target

Before a workout starts, use **Session target** to select either **Duration** or **Distance**. Enter positive whole minutes or a kilometer value such as `12.5`, then tap **Set target**.

The target applies to the next workout only, including a workout started automatically by background monitoring. Once that workout starts, the pending target is cleared. Use **Clear** before starting if you no longer want it.

During the workout, the targeted duration or distance card fills from left to right while continuing to show the current value. At 100% it displays **Target reached** and remains full; recording continues until you finish the workout.

### Manual recording

1. Connect the bike and wait for **Ready**.
2. Tap **Start workout** and ride.
3. Tap **Stop and save** when finished.

The workout is saved locally before a background task exports it to Health Connect. Recording continues while the screen is off.

### Background monitoring

Enable **Background monitoring** to let the app reconnect to the remembered bike and begin recording automatically when bike measurements arrive. Monitoring runs through a foreground notification and resumes after the phone restarts.

If the bike disconnects during a workout, the app waits up to five minutes for it to reconnect. If it does not return, the workout is finalized at the time of the last measurement. Use **Finish now** to end an automatically started workout immediately.

## Workout history

Tap **History** to view completed workouts, newest first. The app loads history in batches of 20; use **Load more** to retrieve older entries.

Each entry shows its date, duration, distance, and Health Connect state:

- **Pending:** waiting to be exported.
- **Synced:** successfully written to Health Connect.
- **Failed:** export failed; open the workout to see the error and tap **Retry sync**.

Workout details include average and maximum speed, cadence, and power when the bike supplied those measurements.

If the workout had a target, its details also show the planned value and whether it was reached.

Workouts recorded by affected older releases may show that detailed metrics are unavailable. Those releases deleted their local sample rows while updating the workout, so the missing historical samples cannot be reconstructed. Workouts recorded after the fix retain their samples.

## Health Connect and Google Fit

FTMS Bike Bridge stores workouts locally and exports completed records to Health Connect. It does not communicate with Google Fit directly.

To display the shared workouts in Google Fit:

1. Open Google Fit.
2. Open **Profile → Settings**.
3. Under **Health Connect**, enable **Sync Fit with Health Connect**.
4. Grant Google Fit access to the relevant exercise and activity data.

Google maintains the current setup steps in [Health Connect on Google Fit](https://support.google.com/fit/answer/12830119). Google Fit may show the session and distance without rendering every cadence or power sample in its own interface.

## App updates

Signed release builds check for a newer stable GitHub release at most once every 24 hours when the app opens or resumes. Tap **Check updates** to check immediately.

When an update is available:

1. Tap **Update** to download the release APK and its published SHA-256 checksum over the current network connection.
2. Wait while the app verifies the checksum, Android package ID, and version.
3. Tap **Install** and follow Android's confirmation flow.

Android may first open the **Install unknown apps** setting so FTMS Bike Bridge can request the update. Installation is disabled while a workout is recording or being finalized. Choosing **Later** hides that release for 24 hours.

Debug builds do not check for or install releases. A version installed before the updater was introduced must be upgraded manually once; updater-enabled releases can handle later upgrades.

## Diagnostics and troubleshooting

Open **Diagnostics** to inspect the latest raw FTMS packet and discovered GATT characteristics. Use **Share diagnostic log** when reporting a bike compatibility problem.

- **Bike not found:** pedal to wake it, disconnect other bike apps, and scan again.
- **Bike connects but metrics remain empty:** inspect and share the diagnostic log; the firmware may use a non-standard FTMS packet layout.
- **Old workout has no detailed metrics:** samples lost by an affected older release cannot be recovered. New rides recorded after the fix should contain them.
- **Workout remains pending or failed:** open **Health permissions**, grant all requested write permissions, then retry from the workout details.
- **Workout is absent in Google Fit:** confirm it exists in Health Connect, then confirm Google Fit synchronization and permissions.
- **Update check fails:** verify internet access and retry with **Check updates**. Releases must contain both the expected APK and matching `.sha256` asset.
- **Android rejects an update:** install the APK signed by the same key as the existing app, or uninstall the old app first. Uninstalling removes local app data.

## Build from source

Development requires:

- Android Studio with JDK 17.
- Android SDK Platform 36 and Android SDK Build-Tools 36.0.0.

Clone the repository, open it in Android Studio, and allow Gradle sync to finish. Run the `app` configuration on an Android 14+ phone, or install a debug build from the command line:

```shell
./gradlew installDebug
```

The project compiles and targets API 36 and has a minimum SDK of API 34.

## Verification and CI

Run the local quality checks with:

```shell
./gradlew spotlessCheck testDebugUnitTest lintDebug
```

Use `./gradlew spotlessApply` to apply Kotlin and repository formatting. Pull requests and pushes to `main` run the same checks in GitHub Actions.

Hardware acceptance should cover:

- A screen-off ride of at least 30 minutes.
- A forced Bluetooth disconnect and reconnect.
- Workout details containing the recorded metric summaries.
- One non-duplicated session in Health Connect and Google Fit.
- A signed update from one release version to the next.

## Publishing signed releases

The release workflow runs for tags matching `vMAJOR.MINOR.PATCH`. It derives Android's version name and version code from the tag, runs the quality checks, builds a signed APK, and publishes the APK plus its SHA-256 checksum to GitHub Releases.

Create a protected GitHub Actions environment named `release` with these secrets:

- `ANDROID_RELEASE_KEYSTORE_BASE64`
- `ANDROID_RELEASE_STORE_PASSWORD`
- `ANDROID_RELEASE_KEY_ALIAS`
- `ANDROID_RELEASE_KEY_PASSWORD`

Create and back up the release key outside this repository:

```powershell
keytool -genkeypair -v -keystore ftms-bridge-release.jks -alias ftms-bridge -keyalg RSA -keysize 4096 -validity 10000
[Convert]::ToBase64String([IO.File]::ReadAllBytes("ftms-bridge-release.jks")) | Set-Clipboard
```

Store the Base64 value and matching credentials in the release environment. Keep the original keystore and passwords in a secure backup: losing the key prevents future APKs from updating existing installations.

After CI succeeds, publish a new semantic version by pushing its tag, for example:

```shell
git tag v0.2.0
git push origin v0.2.0
```

## Protocol and license

The FTMS parser follows the Bluetooth Fitness Machine Service flag layout. The Windows/Unity project [frakw/BLE_FTMS_IndoorBike](https://github.com/frakw/BLE_FTMS_IndoorBike) is useful for comparing real-bike behavior, but none of its WinRT binaries or source are included here.

FTMS Bike Bridge is licensed under the [GNU General Public License v3.0](LICENSE).
