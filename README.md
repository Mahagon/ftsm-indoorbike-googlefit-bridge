# FTMS Indoor Bike Google Fit Bridge

An Android app that records an FTMS-compatible indoor bike over Bluetooth LE and writes the completed workout to Android Health Connect. Google Fit can then display the synchronized stationary-cycling workout.

The first version records duration, speed, cycling cadence, power, and distance. It intentionally does not control resistance, estimate calories, or use the deprecated Google Fit API.

## Requirements

- Android Studio with JDK 17
- Android 17 / API 37 SDK and Android SDK Platform-Tools
- An Android 14+ phone with Bluetooth LE and Health Connect
- A bike exposing the FTMS service (`0x1826`) and Indoor Bike Data characteristic (`0x2AD2`)

## Build and install

1. Open this repository in Android Studio and allow Gradle sync to complete.
2. Connect the phone with USB debugging enabled.
3. Select the `app` run configuration and run it on the phone, or execute `./gradlew installDebug`.
4. Grant Nearby devices, notification, and requested Health Connect permissions.

The package name is `dev.frakw.ftmsbridge`. This repository is GPLv3 licensed.

## Record a workout

1. Wake the bike by pedaling and ensure another app is not connected to it.
2. Tap **Scan**, select the FTMS bike, and wait for **Ready**.
3. Tap **Start workout** and ride. Recording continues while the screen is off.
4. Tap **Stop and save**. The workout is persisted first and then written to Health Connect.
5. In Google Fit, enable **Profile → Settings → Sync Fit with Health Connect** and grant Fit access to exercise and activity data.

Health Connect remains the source of truth. Google Fit may display the session and distance without rendering every cadence or power sample in its own interface.

Completed workouts are also available under **History** in the app. Open a workout to see its duration, distance, synchronization state, and average and maximum speed, cadence, and power when those metrics were recorded.

## App updates

Signed release builds check GitHub Releases for a newer stable version at most once per day. You can also use **Check updates** on the main screen. The app downloads the matching APK and checksum only after you choose **Update**, verifies them, and then opens Android's installer. Android may first ask you to allow FTMS Bike Bridge as an installation source and always asks you to confirm the update.

Debug builds do not check for or install release updates.

## Diagnostics

Open **Diagnostics** in the app to inspect the last raw FTMS packet and the discovered GATT characteristics. Use **Share diagnostic log** when a JC-series firmware exposes different fields or fails to connect.

Common problems:

- **Bike not found:** pedal to wake it, disconnect Kinomap/Zwift or other bike apps, and scan again.
- **No metrics:** share the diagnostic log; the bike may use a non-standard packet layout.
- **Workout remains pending:** open Health permissions, grant all write permissions, then return to the app to retry synchronization.
- **Workout absent in Fit:** confirm it exists in Health Connect first and that Google Fit Health Connect synchronization is enabled.

## Verification

Run all local quality checks with:

```shell
./gradlew spotlessCheck testDebugUnitTest lintDebug
```

Use `./gradlew spotlessApply` to apply Kotlin and repository formatting. Pull requests and pushes to `main` run these checks in GitHub Actions. Configure `quality` as a required status check in the `main` branch protection rules.

Hardware acceptance should cover a 30-minute screen-off ride, a forced Bluetooth disconnect/reconnect, confirmation of the records in Health Connect Toolbox, and confirmation of one non-duplicated workout in Google Fit.

## Signed releases

The release workflow builds and publishes a signed APK plus its SHA-256 checksum when a semantic version tag such as `v1.2.3` is pushed. Create a protected GitHub Actions environment named `release` and add these environment secrets:

- `ANDROID_RELEASE_KEYSTORE_BASE64`
- `ANDROID_RELEASE_STORE_PASSWORD`
- `ANDROID_RELEASE_KEY_ALIAS`
- `ANDROID_RELEASE_KEY_PASSWORD`

Create and back up a release key outside this repository:

```powershell
keytool -genkeypair -v -keystore ftms-bridge-release.jks -alias ftms-bridge -keyalg RSA -keysize 4096 -validity 10000
[Convert]::ToBase64String([IO.File]::ReadAllBytes("ftms-bridge-release.jks")) | Set-Clipboard
```

Paste the clipboard value into `ANDROID_RELEASE_KEYSTORE_BASE64`, store the passwords and alias in the other secrets, and keep the original keystore and passwords in a secure backup. Losing this key prevents future updates from replacing an installed release.

After CI succeeds, create a release with:

```shell
git tag v0.1.0
git push origin v0.1.0
```

## Protocol reference

The FTMS parser follows the Bluetooth Fitness Machine Service flag layout. The Windows/Unity project [frakw/BLE_FTMS_IndoorBike](https://github.com/frakw/BLE_FTMS_IndoorBike) is useful for comparing real-bike behavior but none of its WinRT binaries or source are included here.
