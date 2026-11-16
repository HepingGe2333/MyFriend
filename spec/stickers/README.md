# 表情包（sticker）

表情包是**压缩过的 GIF**：单文件 ≤ 512 KB、长边 ≤ 320 px、帧数 ≤ 60、总时长 ≤ 6 s。
支持静态图（单帧 GIF）与动图。

## 数据

```
stickers(
  id TEXT PK, ownerUserId TEXT?, personaId TEXT?, file TEXT, mime TEXT,
  width INTEGER, height INTEGER, frames INTEGER, bytes INTEGER,
  source TEXT,        -- IMPORTED | MODEL_MADE | FROM_USER_MESSAGE | WEB
  createdBy TEXT,     -- USER | MODEL
  createdAt INTEGER
)
```

文件存 App 私有目录 `files/stickers/<id>.gif`（服务端模式存服务端媒体）。

## 导入与压缩

- 相册/文件选择图片或 GIF → **自动压缩为 GIF**：
  - 长边缩到 ≤320 px，保持宽高比；
  - 帧率 ≤20 fps，帧数 ≤60，总时长 ≤6 s；
  - 调色板 ≤256 色（GIF 8-bit），抖动开；
  - 输出 ≤512 KB（超出则降尺寸/减帧/降色）；
- 微信/QQ 拆出来的 GIF、`webp` 动图、`apng` 同样走这条管线；
- 静态图导入 → 单帧 GIF（或 PNG 展示，存储仍为 GIF）。

## 发送与历史

- 输入框左侧有**独立表情按钮**（与图片按钮分开）；点开是历史表情面板（网格，最近 30 个 + 全部）；
- 点选即发送（不进入文字草稿）；表情消息在聊天里以 240 px 展示，长按可「添加到我的表情」；
- 用户可以把模型发来的表情加入自己的收藏（同一文件、`createdBy` 不变，增加 owner 关联）。

## 模型工具 `sticker`

```json
{ "action": "send|make|save",
  "stickerId": "…（send 用）",
  "url": "https://…（make：下载图片）",
  "baseStickerId": "…（make：基底）",
  "text": "…（make：叠字）",
  "overlayStickerId": "…（make：叠另一张图）",
  "position": "TOP|CENTER|BOTTOM",
  "fromMessageId": "…（save：把用户发过的表情加入收藏）" }
```

- `send`：直接把已有表情作为一条消息发出（`role=ASSISTANT`，`attachments` 指向表情）；
- `make`：下载 URL 图片 / 以已有表情为基底，拼接文字或另一张表情 → 生成新表情（走同一条压缩管线）；
  纯色底 + 文字（meme 风格）允许 `baseStickerId` 为空时用内置空白底；
- `save`：把用户发来的表情加入「我的表情」；
- 结果回灌：`已发送表情` / `做好了（id=…）` / `已收藏`。

## 安全与版权

- `make` 只下载公开 http(s) 图片，走 `WebUrlPolicy`（拒私网/本机/userinfo），≤8 MiB；
- 用户导入的内容只存在本机（服务端模式存自己的服务端）；
- 不做自动去水印、不抓取版权素材站；来源只记录 URL 域名。

## 验收

- 导入 GIF/PNG/WebP → 输出符合上限，尺寸/帧数/字节均有测试；
- 表情按钮与历史面板；发送渲染；长按收藏；
- 模型 `send/make/save` 三条路径；`make` 的叠字与叠图各一条用例；
- 服务端模式下表情走媒体上传/下载。
