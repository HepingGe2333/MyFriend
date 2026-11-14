# Versioning rules (beta `0.x`)

> 中文版：[VERSIONING.md](VERSIONING.md)

MyFriend is currently in **beta**. Version numbers follow the size of a change, not a feeling for how many features landed:

| Change type | Action | Example |
| --- | --- | --- |
| Small change (bug fix, copy tweak, polishing one feature) | **increment the last digit** | `0.10.0` → `0.10.1` |
| Large change (new capability surface, data-structure change, UI restructure) | **increment the middle digit, reset the last to 0** | `0.9.1` → `0.10.0` |
| Major version (the `0` in `0.x`) | **stays 0 unless a maintainer sets it manually** | — |

The first digit only becomes `1.x` when the beta ends and the project ships a stable release. The current version is **0.10.0**, database schema **v10**, backup format **v2**.

## The four places to update on every release

| Location | Field |
| --- | --- |
| `app/build.gradle.kts` | `versionCode` (increment on every release, never decrease), `versionName` |
| `ios/MyFriend.xcodeproj/project.pbxproj` | `CURRENT_PROJECT_VERSION` (equal to Android's `versionCode`), `MARKETING_VERSION` |
| `docs/CHANGELOG.md` | a new entry for this version |
| `README.md` | the version row in the project status table |

`README.md` and `docs/CHANGELOG.md` both have English mirrors (`README.en.md`, `docs/CHANGELOG.en.md`); update them in the same release, otherwise the two language versions disagree about the current version.

## Release steps

1. Update the four places above and their English mirrors.
2. Run Android `./gradlew :app:testDebugUnitTest`.
3. Run iOS `cd ios/MyFriendKit && swift test --disable-sandbox`.
4. If schema or backup format changed, also run the migration tests and `BackupFixtureTest` / `BackupInteropTests`.
5. Confirm both platforms build: `./gradlew :app:assembleDebug` and `xcodebuild ... build`.
6. Tag the release using the repository's existing naming convention.
7. Use the CHANGELOG entry for this version as the release notes.

## Why `versionCode` and `CURRENT_PROJECT_VERSION` must be equal

Both platforms share one backup file and one database schema (v10). Keeping build numbers equal removes the need to distinguish platforms when tracing which build exported a given backup. Build numbers only increase, so upgrade paths stay intact.

Schema and backup have their own independent versions:

| Version | When to increment |
| --- | --- |
| `MyFriendDatabase.version` / iOS `user_version` | whenever the table structure changes, together with one migration and one migration test (both platforms) |
| `backup.formatVersion` | whenever the backup structure changes, together with `fixtures/backup/` and the interop tests on both platforms |

The schema version has **no** relation to the app version: `0.10.0` still runs schema v10. The only sources of truth for the version number are the two build files above; the About screen and the backup's `appVersion` derive from the build number instead of hand-written strings.
