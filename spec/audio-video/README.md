# 音频与视频输入（0.13）

## 目标

用户可以直接发语音/视频；模型**听得到声音、看得到画面**，而不是只拿转写文本。

## 附件

`messages.attachmentsJson` 的 `MessageAttachment` 增加 `kind`（IMAGE/AUDIO/VIDEO）与 `durationMs`；
mime 允许 `audio/m4a`、`audio/mp4`、`audio/mpeg`、`video/mp4`、`video/quicktime`。

## 压缩与存储

- 音频：AAC 单声道 16 kHz、≤5 分钟、≤8 MB；本机缓存 + 服务端 `/v1/media?kind=AUDIO`。
- 视频：720p H.264、≤2 分钟、≤64 MB；本机 + `/v1/media?kind=VIDEO`。
- 服务端媒体类型白名单扩展：`image/jpeg|png|webp|gif`、`audio/m4a|mp4|mpeg`、`video/mp4|quicktime`；
  音频/视频单文件放宽到 64 MiB（服务端 body 上限相应提高），图片仍 2 MiB。

## 模型侧

- `listen(audioId)`：客户端/服务端把音频转成 provider 支持的多模态输入：
  - OpenAI 兼容：`content` 里的 `input_audio`（base64 + format）；
  - 不支持音频的模型：明确报错「这个模型听不了语音」，不要伪造转写；
- `watch(videoId)`：抽关键帧（场景变化优先，最多 8 帧，每帧 ≤1024px JPEG）+ 音轨（可选）；
  以多张 `image_url` 形式发给模型；不支持的模型同上；
- 工具结果回灌：「听到：…」/「看了 N 帧」。

## 客户端

- 输入栏：语音按钮（按住录音，松开发送）、视频按钮（选视频→压缩→上传→发送）；
- 气泡：音频显示时长与播放按钮；视频显示首帧与播放；
- 播放：本地文件或带鉴权的服务端媒体 URL。

## 验收

- 录音/选视频 → 压缩 → 上传 → 发送 → 气泡可播放；
- 模型 `listen/watch` 工具：支持模型收到多模态输入；不支持模型明确报错；
- 服务端媒体类型/大小校验与拒绝；
- 两种模式的附件渲染。
