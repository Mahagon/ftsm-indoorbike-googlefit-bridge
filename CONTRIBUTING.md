# Contributing

Thanks for helping improve FTMS Bike Bridge. Bug reports, bike compatibility details, documentation updates, and focused code changes are welcome.

## Before you start

For significant changes, open an issue first so the approach and next release version can be coordinated. Search existing issues before reporting a duplicate.

Do not report vulnerabilities publicly. Follow the confidential process in [SECURITY.md](SECURITY.md).

## Development setup

Development requires Android Studio, JDK 17, Android SDK Platform 37, and Android SDK Build-Tools 37.0.0. Clone the repository, open it in Android Studio, and allow Gradle sync to finish.

Build or install a debug version with:

```shell
./gradlew assembleDebug
./gradlew installDebug
```

## Branches and pull requests

Human-authored pull-request branches must use the next release version in exact `MAJOR.MINOR.PATCH` form, such as `0.2.0`. The version must be greater than the latest release tag. Coordinate before claiming a version so concurrent contributions do not use the same release number. Dependabot branches are exempt.

Keep changes focused and use clear commit messages. In the pull request:

- Explain the problem and the chosen solution.
- Link related issues.
- Describe automated and manual testing.
- Include screenshots or recordings for visible UI changes.
- Call out Bluetooth, background-service, Health Connect, permission, or upgrade behavior that may be affected.

After the pull request is merged into `main`, automation creates the matching `vMAJOR.MINOR.PATCH` tag and starts the signed release workflow.

## Quality checks

Apply formatting when needed and run the same quality checks used by CI:

```shell
./gradlew spotlessApply
./gradlew spotlessCheck testDebugUnitTest lintDebug
```

Changes affecting Android behavior should also pass API 36 instrumentation tests. Bluetooth, workout recording, background monitoring, Health Connect, and updater changes should be tested on a physical Android 14+ device when possible.

For FTMS compatibility reports, include the bike make and model, Android version, app version, expected and observed behavior, and a redacted log from **Diagnostics → Share diagnostic log**.

## Licensing

By contributing, you agree that your contribution is licensed under the repository's [GNU General Public License v3.0](LICENSE).
