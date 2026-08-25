# Privacy Policy

## Last updated: August 25, 2026

FTMS Bike Bridge is an open-source Android application that records workout data from compatible indoor bikes and can save that data to Android Health Connect. This policy explains what data the app handles, where it goes, and how you can remove it.

## Summary

FTMS Bike Bridge has no user accounts, advertising, analytics, data sales, or developer-operated data server. The app does not automatically send workout data, Bluetooth device information, or diagnostic logs to the project maintainer. Data is handled on your device, by services you choose to use, or by an app you explicitly select when sharing diagnostics.

## Bluetooth and bike data

The app scans for nearby devices that advertise the Bluetooth Fitness Machine Service. During a scan it temporarily handles the device name, Bluetooth address, and signal strength reported by Android. When you choose a bike, its Bluetooth address is stored in the app's private preferences so the app can reconnect to it. Those preferences are not included in the app's cloud-backup rules.

While connected, the app handles FTMS measurements supplied by the bike. Depending on the bike, these can include timestamps, elapsed time, speed, cadence, power, distance, and energy or calorie values. The app does not use Bluetooth scans to determine your location.

## Workout history

Recorded workouts are stored in an Android Room database in the app's private storage. A workout can contain an internal identifier, start and end times, target duration or distance, calculated distance and calories, Health Connect synchronization state or error text, and timestamped samples for speed, cadence, power, distance, and energy.

Android's application sandbox protects this local data from ordinary access by other apps. The local database is not separately encrypted by FTMS Bike Bridge.

## Health Connect

With your permission, completed workouts are written to the on-device Health Connect store as stationary cycling exercise sessions. Available data can include exercise time, distance, speed, cycling cadence, power, and total calories. The app also reads its own exercise-session and distance records from Health Connect when you ask it to verify a saved workout.

You can grant or revoke access in Android's Health Connect settings. Revoking access stops future access but does not automatically remove records already written to Health Connect. Delete those records separately through Health Connect if you no longer want them there. Other apps can access these records only when you grant those apps the corresponding Health Connect permissions.

## Android backup and device transfer

The workout database is eligible for Android cloud backup only when the device provides the required encryption capabilities. Android stores an eligible backup in the private backup area of your Google account and controls when backup and restoration occur. The workout database is also eligible for Android's device-to-device transfer. FTMS Bike Bridge and its maintainer cannot inspect or initiate these operations.

The remembered bike address, monitoring preference, pending target, retention setting, and update preferences are not included by the app's backup rules.

## GitHub update requests

Signed release builds contact GitHub automatically at most once every 24 hours while the app is used, and whenever you manually check for an update. The request asks for metadata about the latest release. If you choose to update, Android downloads the selected APK and checksum from GitHub's download infrastructure.

FTMS Bike Bridge does not attach workout data, bike information, or diagnostic logs to these requests. As with any internet connection, GitHub and its infrastructure receive normal network information such as your IP address and may process it under the [GitHub General Privacy Statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement).

## Diagnostic sharing

Connection diagnostics and the most recent raw FTMS packet are kept in memory for troubleshooting. They are not uploaded automatically. When you tap **Share diagnostic log**, the app opens Android's share chooser and sends the report only to the app or recipient you select.

A diagnostic report can contain timestamps, connection status and errors, the bike name, Bluetooth address, signal strength, discovered service characteristics, and raw FTMS packet data. Review the destination before sharing. After you share a report, the selected recipient handles it under that recipient's own privacy practices.

## Retention and deletion

The configured training-history retention removes the oldest completed workouts after they have been saved to Health Connect. Active workouts, workouts not yet saved to Health Connect, and the newest completed workout remain protected even when they exceed the configured limit.

You can remove the app's local data immediately by clearing its storage in Android settings or uninstalling it. Android may later restore an eligible cloud backup unless you also manage or remove that backup through your device or Google backup settings. Clearing or uninstalling the app does not delete records already stored in Health Connect; manage those records separately in Health Connect.

## Security

The app uses Android private storage, disallows cleartext network traffic, and uses HTTPS for GitHub requests. Downloaded updates are checked against the published SHA-256 checksum, package identity, version, and installed-app signing certificate before installation. No method of storage or transmission can be guaranteed to be completely secure.

## Changes to this policy

Material changes will be published in this repository and included in a later app release. The date at the top identifies the latest revision.

## Contact

For an ordinary privacy question, open a [GitHub issue](https://github.com/Mahagon/ftsm-indoorbike-googlefit-bridge/issues) without including personal or sensitive information. For a sensitive privacy or security report, use GitHub's [private vulnerability reporting form](https://github.com/Mahagon/ftsm-indoorbike-googlefit-bridge/security/advisories/new).
