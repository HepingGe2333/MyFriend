# Contributing to MyFriend

This document is for contributors who plan to send code, documentation or translations. MyFriend ships on two platforms (Android and iOS) that share their data contracts and behavioural specification, so most rules are about changing both sides together.

> 中文版：[CONTRIBUTING.md](CONTRIBUTING.md)

## Required reading

| File | Why |
| --- | --- |
| [README.en.md](README.en.md) | Positioning, build commands, known limitations |
| [docs/ARCHITECTURE.en.md](docs/ARCHITECTURE.en.md) | Layers, data flow, cross-platform contracts |
| [docs/README.md](docs/README.md) | Documentation index (including contracts and sub-project docs) |
| [docs/design/roadmap-1.9.md](docs/design/roadmap-1.9.md) | Historical behaviour spec and acceptance table for 0.9.0; source of the "deliberately removed" list |
| [docs/IOS-PARITY.md](docs/IOS-PARITY.md) | What must match across platforms, platform limits, known iOS gaps |
| [docs/I18N.en.md](docs/I18N.en.md) | String and localisation rules |

## Development environment

- **Android**: JDK 17, Android SDK Platform 35, the bundled Gradle Wrapper (Gradle 8.9 / AGP 8.7.3). `local.properties` needs `sdk.dir=...`; the file is never committed.
- **iOS**: Xcode 16+, deployment target iOS 16.0. The iOS side has **no third-party dependencies**: SQLite uses the system `SQLite3`, networking uses `URLSession`, serialisation uses `Codable`. `ios/MyFriendKit` is a local SPM package so logic tests run directly on macOS.
- **Reference server**: Node.js (`harness/server.mjs` uses only the standard library and has no `package.json` dependencies).
- **agentd**: JDK 17 (Kotlin/JVM module using only the JDK's `com.sun.net.httpserver`).

## Build and test

Run at least the following before sending changes:

```bash
# Android unit tests + build
./gradlew :app:testDebugUnitTest :app:assembleDebug

# iOS: build the shared decision framework first (Kotlin Multiplatform; the app links it)
ios/scripts/build-agentcore-framework.sh

# iOS logic tests (run on macOS, no simulator, no framework needed)
cd ios/MyFriendKit && swift test --disable-sandbox

# iOS app build
cd ios && xcodebuild -project MyFriend.xcodeproj -scheme MyFriend \
  -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build
```

`AgentCoreKit` is the Kotlin Multiplatform output of `agentcore/` — it *is* the iOS decision logic. The framework is a
build artefact and is not committed; the Xcode project has a Run Script that builds it when missing.

When changing the agent core service, also run `./gradlew :agentd:test` (plain JVM, no emulator needed).

When database migrations are involved, also run on an emulator or device:

```bash
./gradlew :app:connectedDebugAndroidTest
```

When the backup format or a cross-platform contract changes, additionally run:

```bash
./gradlew :app:testDebugUnitTest --tests "*BackupFixtureTest*"
cd ios/MyFriendKit && swift test --disable-sandbox --filter BackupInteropTests
```

`--disable-sandbox` exists for running the local SPM package tests in restricted environments; it can be omitted when the environment allows.

### What CI runs, and how to reproduce it locally

On every push to `main` / `dev/**` and every pull request targeting `main`, GitHub Actions runs [`.github/workflows/ci.yml`](.github/workflows/ci.yml). A final `ci-ok` job passes only when both platform jobs pass, so it can be used as the required merge check; a newer run on the same branch cancels the older one.

- **`android`** (`ubuntu-latest`, JDK 17): restores the Gradle cache, runs unit tests (`:app:testDebugUnitTest`, `:agentcore:test`, `:agentd:test`), then the build + lint (`:app:assembleDebug`, `:agentd:build`, `:app:lintDebug`). Test and lint reports are uploaded as artifacts even on failure.
- **`ios`** (`macos-latest`, JDK 17 + the runner's Xcode): restores `~/.gradle` / `~/.konan`, builds `AgentCoreKit.xcframework` with `ios/scripts/build-agentcore-framework.sh`, runs `swift test --disable-sandbox` in `ios/MyFriendKit`, and finally compiles the app and test target with `xcodebuild ... build-for-testing`. The `.xcresult` and build logs are kept on failure too.

Reproduce locally with the same task names (CI only adds `--console=plain`):

```bash
# Android
./gradlew :app:testDebugUnitTest :agentcore:test :agentd:test
./gradlew :app:assembleDebug :agentd:build :app:lintDebug

# iOS: the framework must be built before Xcode links the app
ios/scripts/build-agentcore-framework.sh
(cd ios/MyFriendKit && swift test --disable-sandbox)
xcodebuild -project ios/MyFriend.xcodeproj -scheme MyFriend \
  -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -derivedDataPath ios/.build/DerivedData CODE_SIGNING_ALLOWED=NO build-for-testing
```

`build-for-testing` only compiles and does not boot a simulator; UI and database-migration cases still need to run locally (see `connectedDebugAndroidTest` and Xcode Test above). If your local Xcode does not have an `iPhone 17 Pro` simulator, use any iPhone name from `xcrun simctl list devices available` for `-destination`; the Gradle task names stay the same as CI.

## Code style

- **Android**: Kotlin + Compose, `kotlin.code.style=official`. Algorithms and boundary conditions belong in pure `domain/` functions with unit tests, not inside Composables; Composables only own state and layout.
- **iOS**: Swift 5.9. `ios/MyFriendKit` must not import any UI framework; the SwiftUI layer must not reimplement domain logic and should reuse Kit types directly.
- **Comments**: keep them in Chinese and explain *why*, not *what*. Numbers such as thresholds, clamp ranges and time windows should point at the specification section in a comment (for example a section of `docs/design/roadmap-1.9.md`).
- **Dependencies**: no new Android third-party library without discussion; iOS stays free of third-party dependencies; `harness/` stays dependency-free.
- **Tests**: new pure logic requires unit tests; follow existing naming (Android `XxxTest.kt`, iOS `XxxTests.swift`).

## Commit messages

Use [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <summary>

<body>
```

- `type`: `feat` / `fix` / `refactor` / `test` / `docs` / `build` / `chore` / `perf`.
- `scope` should name the module or platform: `android` / `ios` / `agentd` / `backup` / `memory` / `i18n` / `docs`.
- `summary` states the outcome in one line; the body may be written in Chinese and should explain motivation, impact and verification.
- One commit does one thing; keep formatting separate from behavioural changes.

Example:

```
fix(memory): 修正整理后归档记忆仍进入提示词

MemoryRepository.getRelevant 之前读 getForPersona，
归档条目会被重新注入 system。改为 getForPrompt，
并补一条 recall 深度用例。
```

## Pull request flow

1. Branch from the default branch (`main`) using `type/short-description` (for example `docs/bilingual-readme`).
2. Run the builds and tests listed above locally; include the commands and their results.
3. The PR description covers: motivation, behavioural change, impact surface (Android / iOS / schema / backup / strings), verification, linked issue.
4. UI changes must state whether both platforms match. If they cannot match, add a row to the "platform differences" or "not connected yet" table in [docs/IOS-PARITY.md](docs/IOS-PARITY.md).
5. Changes to the specification must update the relevant `docs/ROADMAP-*.md` or [docs/ARCHITECTURE.en.md](docs/ARCHITECTURE.en.md) in the same PR.
6. Do not merge before review; maintainers squash on merge.

## Do not commit, do not modify

The following never enter the repository (already excluded by `.gitignore`; do not `git add -f` them either):

- any API key, `keystore.properties`, `*.jks` / `*.keystore`, `local.properties`;
- `dist/`, APK / AAB / IPA, `app/build/`, `.gradle/`, `ios/.build/`;
- `docs/screenshots/` and any other screenshots (the repository does not publish screenshots, and docs must not reference image files);
- Local toolchain/cache directories (for example an SDK or Gradle cache placed inside the checkout).
- Do not change `LICENSE` without discussion; license changes are a maintainer decision.

## Data structure changes

The database schema is currently **v10** and the backup format is **v2**. The workflow:

1. **Change both platforms**: Android (Room entities, DAOs, `MyFriendDatabase.version`) and iOS (`Models.swift`, `SQLiteStore`, the DDL and `Schema.version` in `Schema.swift`) must land in the same PR.
2. **Schema +1**: Room `@Database(version = N+1)` and iOS `Schema.version = N+1` (written into `user_version` when the database opens; the two numbers must still match); `app/schemas/<database>/<N+1>.json` is exported by KSP and must be committed with the PR.
3. **Write the migration**: add `MIGRATION_N_(N+1)` in `MyFriendDatabase` on Android; on iOS add the matching logic to the DDL and `migrateLegacyData` in `Schema.swift` (adding tables/columns, idempotent). Destructive fallback or "drop and recreate" is forbidden.
4. **Write migration tests**: Android covers the old database's tables and data in `app/src/androidTest/.../MyFriendDatabaseMigrationTest.kt`; iOS adds cases in `ios/MyFriendKit/Tests/MyFriendKitTests/Data/SchemaMigrationTests.swift`. Migrations must be idempotent.
5. **Backup**: decide whether field additions/removals affect `BackupCodec`. If they do, bump `formatVersion`, change both `BackupCodec.*` implementations together, and **regenerate** `fixtures/backup/android-export-v3.json` and `ios-export-v3.json`. v1/v2 samples are inputs only and are never rewritten; the current format is v3.
6. **Verify**: `BackupFixtureTest` (Android) and `BackupInteropTests` (iOS) must both pass, and old backups must still load.

Field names, enum values and defaults follow [docs/design/roadmap-1.9.md](docs/design/roadmap-1.9.md) and the two `BackupCodec` implementations; field names must be identical on both sides.

## Strings and i18n

The UI is available in Chinese (default/base) and English, following the system language; there is no in-app switch. When adding or changing user-visible strings:

1. **Android**: add the same key to `app/src/main/res/values/strings.xml` (Chinese) and `app/src/main/res/values-en/strings.xml` (English); keys use snake_case with a screen/component prefix (`home_` / `chat_` / `persona_` / `settings_` / …), seed persona copy goes into `seed.xml`.
2. **iOS**: add the same entry to `ios/MyFriend/Resources/zh-Hans.lproj/Localizable.strings` and `en.lproj/Localizable.strings`. iOS uses the Chinese source text as the key.
3. Key sets and placeholder sets must match (placeholders may be reordered but never dropped). Guard tests: Android `app/src/test/java/com/myfriend/app/i18n/StringResourceParityTest.kt`, iOS `ios/MyFriendKit/Tests/MyFriendKitTests/I18N/LocalizationResourceTests.swift`.
4. The UI layer must not hard-code Chinese. Guard tests: Android `ChineseLiteralGuardTest.kt`, iOS `UIChineseLiteralGuardTests.swift`. Fixed strings that must stay out of the resources (logs, model input) are declared explicitly in a whitelist.
5. **Not translated**: code comments, logs, model-prompt scaffolding in `ios/MyFriendKit` and Android `domain/`, tool names and JSON field names. The model's language is controlled by the language-directive section appended to `system`, and that section **follows the UI language**: the Android template lives in `prompt_language_directive` under `values` / `values-en`, the iOS one under the same semantic key in `Localizable.strings`. The wording is identical across platforms; only the placeholder syntax differs (Android `%1$s`, iOS `%1$@`).
6. See [docs/I18N.en.md](docs/I18N.en.md) for the full strategy and known limitations.

## Do not reintroduce removed designs

The following capabilities were **deliberately removed**; they are not backlog items. Reintroducing them breaks the product contract and requires an issue plus explicit maintainer approval:

- quotas, budgets, per-token pricing, over-budget blocking (usage counts tokens only);
- memory switches (automatic extraction / daily consolidation / web-search scoring) and reply pacing;
- an explicit cap on daily proactive messages (now derived from `proactiveLevel`);
- relationship timelines, relationship reports, intimacy scores;
- TTS voice, persona store;
- confirmation dialogs for the persona's own decisions (not replying, sleeping, staying silent).

The full removal list and rationale are in section one of [docs/design/roadmap-1.9.md](docs/design/roadmap-1.9.md).

## Versioning and releases

The scheme is deliberately simple and predictable during the beta:

- The major version stays `0` during the beta; small changes increment the last digit (`0.10.0` → `0.10.1`), large changes increment the middle digit and reset the last (`0.9.1` → `0.10.0`).
- `versionCode` and `CURRENT_PROJECT_VERSION` increment on every release and stay equal; the schema version is independent of the app version.

Release checklist:

1. Bump the version in **4 places**: `versionCode` / `versionName` in `app/build.gradle.kts`; `CURRENT_PROJECT_VERSION` / `MARKETING_VERSION` in `ios/MyFriend.xcodeproj/project.pbxproj`; a new entry in `docs/CHANGELOG.md`; the project status in `README.md`.
2. Update the bilingual mirrors: `docs/CHANGELOG.en.md`, `README.en.md` (plus any version-dependent English document).
3. Run both test suites: `./gradlew :app:testDebugUnitTest` and `cd ios/MyFriendKit && swift test --disable-sandbox`; add migration and fixture tests when schema or backup changed.
4. Confirm both platforms build: `./gradlew :app:assembleDebug` and `xcodebuild ... build`.
5. Tag the release with the existing repository naming convention.
6. Push the tag and publish release notes taken from the CHANGELOG entry.

## License

Contributions are accepted under [Apache-2.0](LICENSE).
