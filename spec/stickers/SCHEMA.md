# 表情包 schema v16

```sql
CREATE TABLE IF NOT EXISTS stickers (
  id TEXT NOT NULL PRIMARY KEY,
  ownerUserId TEXT,
  personaId TEXT,
  file TEXT NOT NULL,            -- 本机文件路径或 /v1/media/<id>
  mime TEXT NOT NULL,            -- image/gif（静态也是单帧 gif）
  width INTEGER NOT NULL DEFAULT 0,
  height INTEGER NOT NULL DEFAULT 0,
  frames INTEGER NOT NULL DEFAULT 1,
  bytes INTEGER NOT NULL DEFAULT 0,
  source TEXT NOT NULL,          -- IMPORTED | MODEL_MADE | FROM_USER_MESSAGE | WEB
  createdBy TEXT NOT NULL,       -- USER | MODEL
  createdAt INTEGER NOT NULL
);
CREATE INDEX index_stickers_createdAt ON stickers(createdAt);
CREATE INDEX index_stickers_personaId ON stickers(personaId);
```

- 两端列名/顺序/可空/索引名逐字一致；备份 v6 起携带（本轮可先不进备份，但要在 CHANGELOG 注明）。
