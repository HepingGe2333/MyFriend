## What changed

<!-- One or two sentences. Link the issue or the changelog entry when there is one. -->

## Platforms touched

- [ ] Android (`app/`)
- [ ] iOS (`ios/`)
- [ ] Shared decision layer (`agentcore/`)
- [ ] Service (`agentd/`)
- [ ] Docs only

## Checks

- [ ] `./gradlew :app:testDebugUnitTest :agentcore:test :agentd:test` passes
- [ ] `cd ios/MyFriendKit && swift test --disable-sandbox` passes
- [ ] `./gradlew :app:assembleDebug :app:lintDebug` and the iOS Debug build succeed
- [ ] If a table/column changed: schema version bumped, migration + migration test added, both platforms updated
- [ ] If the backup format changed: `formatVersion` bumped, `fixtures/backup/` regenerated, both interop tests updated
- [ ] If a UI string was added: both `values*` (Android) and both `*.lproj` (iOS) updated
- [ ] No secrets, `local.properties`, build outputs, `dist/` or screenshots are included

## Notes for reviewers

<!-- Trade-offs, follow-ups, anything you deliberately did not do. -->
