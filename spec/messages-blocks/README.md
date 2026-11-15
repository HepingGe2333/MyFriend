# Message blocks and uncapped search

Language-neutral contract for how conversation history is stored, paged and searched. Both platforms must implement
exactly this; the point is that history is read **a block at a time**, both directions, and that search has **no
candidate cap** — it walks the blocks on disk and keeps only what the page needs.

## 1. Logical model

Messages of a session are grouped into **blocks** of at most `BLOCK_SIZE = 200` messages, in insertion order.

| Table | Columns |
| --- | --- |
| `messages` | existing columns + `blockIndex INTEGER NOT NULL DEFAULT 0` |
| `message_blocks` | `sessionId TEXT NOT NULL`, `blockIndex INTEGER NOT NULL`, `firstMessageId TEXT NOT NULL`, `lastMessageId TEXT NOT NULL`, `firstCreatedAt INTEGER NOT NULL`, `lastCreatedAt INTEGER NOT NULL`, `messageCount INTEGER NOT NULL`, primary key `(sessionId, blockIndex)` |

Block metadata is **derived from messages** but stored so that a page can be located without scanning:

- Block `b` of a session contains the messages whose `blockIndex = b`, ordered by `(createdAt, id)`.
- `first…`/`last…` are the boundary message of that block after the most recent insert or delete.
- Blocks are numbered consecutively from `0`; a block that becomes empty is deleted, leaving a **gap** in numbering —
  blocks are never renumbered.

## 2. Assignment rules

**Append** (new message in a session):

1. Let `b` be the highest `blockIndex` of that session (no blocks → `b = 0`, create it).
2. If block `b` has `messageCount < BLOCK_SIZE`, insert into `b`; otherwise create block `b + 1` and insert there.
3. Update that block's `lastMessageId`, `lastCreatedAt`, `messageCount`; if the block was just created also set
   `firstMessageId`/`firstCreatedAt`.

**Insert at an anchor** (editing a user message re-creates it after the anchor):

- Assign the anchor message's `blockIndex`, then refresh that block's metadata (`messageCount + 1`; adjust `last…` if the
  new message sorts after the old last message).

**Delete**:

- Decrement `messageCount`; if the deleted message was the block's first/last, recompute `first…`/`last…` from the
  remaining rows of that block. If the block becomes empty, delete the block row and leave the numbering gap.

No rebalancing is required: blocks may shrink below `BLOCK_SIZE` and only the last block grows on append.

## 3. Paging (block mode)

- **Initial load**: the newest block only (`MAX(blockIndex)`), returned oldest-to-newest.
- **Older**: when the user reaches the top, load `blockIndex - 1` (one block per page). Repeat while blocks exist.
- **Newer**: when the user reaches the bottom of a window that is not at the newest block, load `blockIndex + 1`.
- A page is exactly one block (or less, if the block was partially deleted). The UI shows the loaded blocks concatenated
  in `(blockIndex, createdAt, id)` order.
- Loading is by `(sessionId, blockIndex)`, never by offset; the number of rows read for a page must be independent of
  session length.

## 4. Search (no candidate cap)

Search walks blocks and never materialises the whole history:

1. Enumerate the blocks of the (optionally persona/time-filtered) sessions in ascending `blockIndex`.
2. For each block, load its messages (one block at a time), keep only those that match the query, and rank them with the
   existing `SearchRanking` rules (matched term count ↓, `ChineseTerms.relevance` ↓, `createdAt` ↓, `id` ↑).
3. Maintain the current page's result set plus a bounded "next" frontier; after all blocks have been visited, return the
   page.
4. Pagination uses a **keyset cursor** `(matchedTermCount, relevance, createdAt, id)` rather than an offset: a later page
   re-walks the blocks and keeps only rows that sort strictly after the cursor. This is what keeps the result set
   complete without a cap.
5. A block's rows are released before the next block is read, so peak memory is bounded by one block plus the page
   (plus the current frontier), not by history size.

`spec/search/cases/*.json` remains the behavioural contract for snippets and ordering; this document adds the storage
and traversal contract.

## 5. Migration (schema v11)

Both platforms migrate `v10 → v11`:

1. Add `messages.blockIndex` with default `0`.
2. Create `message_blocks`.
3. Backfill: for each session, order messages by `(createdAt, id)` and assign `ROWNUM / BLOCK_SIZE` to `blockIndex`
   (0-based), then build the block rows from that assignment.
4. Delete orphan rows in `message_blocks` (sessions that no longer exist) — there should be none.

Migration must be idempotent for an empty database and must preserve every message. Backup format is unchanged (`v2`):
block metadata is derived and is rebuilt on import by applying the append rules in message order.
