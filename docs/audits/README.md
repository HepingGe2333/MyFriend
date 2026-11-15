# Audit records

Independent defect and security audits of this codebase, kept so that:

- the maintainers and reviewers can see which risks were known at a given version;
- the next audit starts from "what has already been verified" instead of re-deriving it;
- every fix can be traced back to a finding (commits quote the id, e.g. `P1-3`).

Each report was produced by someone who did **not** write the code under review, with no permission to change it.

## Reports

| File | Scope | Version audited | Status |
| --- | --- | --- | --- |
| [0.11.1-android.md](0.11.1-android.md) | Android: `:agentcore` boundary, `AgentCoreClient`, the 0.11.0 fixes | 0.11.0 | fixed in 0.11.1 |
| [0.11.1-ios.md](0.11.1-ios.md) | iOS: agent-core client layering, paging/refresh, security | 0.11.0 | fixed in 0.11.1 |
| [0.11.1-security-contract.md](0.11.1-security-contract.md) | `agentd` exposure, remote clients, path/URL policies, contract drift | 0.11.0 | fixed in 0.11.1 |
| [0.10.2-android.md](0.10.2-android.md) | Android: concurrency, lifecycle, data integrity, error handling, i18n | 0.10.1 + search | fixed in 0.10.2 / 0.11.0 |
| [0.10.2-ios.md](0.10.2-ios.md) | iOS: concurrency, memory, background tasks, notifications, SQLite | 0.10.1 + search | fixed in 0.10.2 / 0.11.0 |
| [0.10.2-security-contract.md](0.10.2-security-contract.md) | Both: secrets, network, WebView, paths, privacy, contracts | 0.10.1 + search | fixed in 0.10.2 / 0.11.0 |

## How to read a report

| Level | Meaning |
| --- | --- |
| **P0** | Data loss, crash, or a write that silently disappears. Fix before shipping. |
| **P1** | Wrong behaviour a user can hit, a resource problem that grows over time, or a security weakness that needs a second mistake to become exploitable. Fix in the next release. |
| **P2** | Robustness, performance or polish. Schedule it or accept it explicitly. |

Every finding carries a `file:line`, a concrete trigger, why the code is wrong, and a one-line suggested fix. Reports
also list "checked and not a defect" items so the next reviewer does not repeat the work, and a closing section on
honest leftovers.

## Accepted risks

Recorded so they are not re-reported as new findings:

| Item | Why it is accepted |
| --- | --- |
| iOS has no `persona_revisions` / `persona_pending_patches` tables | iOS implements neither persona version history nor pending patches; the tables would be dead weight and are not part of the backup contract. |
| iOS `chat_sessions` keeps legacy sync columns | Leftovers from the removed harness feature; dropping columns in SQLite needs a table rebuild and buys nothing. Never read. |
| Search ranks at most the newest 500 candidates | Ranking needs term counts SQL cannot express; scanning more turns one search into several full-table scans. The UI says so when results are truncated. |
| Search token matching can cross punctuation | Query terms match independently by contract, so `100%` can recall `1000`. Wildcards themselves are escaped. |
| `agentd` has no request rate limiting | Four simultaneous half-open connections can delay `/health` by roughly one read deadline; the service is loopback-only by default and documented for trusted networks only. |
| URL policy cannot detect DNS names that resolve to loopback | Doing so would require making a DNS query inside a pure policy function; such names are documented as a residual risk. |
| iOS SQLite uses one serialised connection | Long import transactions can block main-thread writes and reads can see uncommitted rows; fixing it needs WAL/snapshot semantics and is tracked, not silently ignored. |

## Process

Audits are requested by the maintainers, run read-only (an auditor may not change the code under review), and each
finding is fixed together with a test before the next release. The reports themselves are the record; there is no
separate process document in this repository.
