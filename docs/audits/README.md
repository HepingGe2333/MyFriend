# Audits

Independent defect and security audits of the codebase. Each report is written by someone who did
**not** write the code under review, and is kept in the repository so that:

- reviewers can see which risks were known at a given version;
- the next audit can start from "what we already verified" instead of re-deriving it;
- fixes can be traced back to a finding (the fix commits quote the ID, e.g. `P1-3`).

## Reports

| File | Scope | Version audited |
| --- | --- | --- |
| [`0.10.2-android.md`](0.10.2-android.md) | Android: concurrency, lifecycle, data integrity, error handling, resources, i18n | 0.10.1 + search feature |
| [`0.10.2-ios.md`](0.10.2-ios.md) | iOS: concurrency, memory, background tasks, notifications, SQLite transactions, security | 0.10.1 + search feature |
| [`0.10.2-security-contract.md`](0.10.2-security-contract.md) | Both: secrets, network, WebView, file paths, privacy; cross-platform schema/backup/i18n contracts | 0.10.1 + search feature |

## Severity

| Level | Meaning |
| --- | --- |
| **P0** | Data loss, crash, or a write that silently disappears. Fix before shipping. |
| **P1** | Wrong behaviour a user can hit, a resource problem that grows over time, or a security weakness that needs a second mistake to become exploitable. Fix in the next release. |
| **P2** | Robustness, performance or polish. Schedule or accept explicitly. |

Every finding must carry a `file:line`, a concrete trigger, why the current code is wrong, and a
one-line suggested fix. A finding without evidence is not a finding — reports also list
"checked and not a defect" items so the next auditor does not repeat the work.

## Status

| Report | Fixed in |
| --- | --- |
| `0.10.2-*` (1 P0, 17 P1) | 0.10.2 |
| `0.10.2-*` remaining P2 items | 0.11.0 (except the accepted risks listed below) |

## Process

1. An audit is requested with a scope ("read-only, find defects, do not modify code").
2. Findings are written to `docs/audits/<version>-<scope>.md`; nothing else in the repository changes.
3. P0/P1 findings are fixed with a test each; the fix commit references the finding ID.
4. A later version re-audits the same scope; the previous report stays untouched.

## Known, deliberately accepted risks

These are recorded here so they are not re-reported as new findings:

| Item | Why it is accepted |
| --- | --- |
| iOS has no `persona_revisions` / `persona_pending_patches` tables | iOS does not implement persona version history or pending patches; the tables would be dead weight and are not part of the backup contract. |
| iOS `chat_sessions` retains legacy sync columns | They are leftovers from the removed harness feature; dropping columns in SQLite requires a table rebuild and buys nothing. They are never read. |
| Search ranks at most the newest 500 candidates | Ranking needs term counts that SQL cannot express; scanning more turns one search into several full-table scans. The UI says so when results are truncated. |
| Search token matching can "cross" punctuation | Query terms are matched independently (by contract), so `100%` can recall `1000`. Wildcards themselves are escaped. |
