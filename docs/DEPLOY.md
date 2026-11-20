# 服务端部署依赖：视频解码与中文字体

> 适用对象：以 `agentd` 作为**远程/独立服务端**运行的部署者。
> **客户端本地模式不受本文任何一条影响**：Android 用 `MediaMetadataRetriever`、iOS 用
> `AVAssetImageGenerator` 抽帧，表情叠字用系统字体（Android `Canvas`、iOS CoreText），
> 不需要 ffmpeg，也不依赖 JRE 字体。

## 0. 先判断：哪些功能需要这些依赖

| 能力 | 客户端本地模式 | 服务端模式（agentd） |
| --- | --- | --- |
| 视频 `watch` 抽帧 | 系统 codec（H.264/HEVC 等） | **需要系统 `ffmpeg`** |
| 音频 `listen` | 系统解码 + `input_audio` | 不需要 ffmpeg（直接读音频字节） |
| 表情 `sticker.make` 叠字 | 系统字体（中文正常） | **需要 JRE 能加载 CJK 字体** |
| 表情 `sticker.make` 叠图 / 动图逐帧 | 系统图像框架 | 不需要额外依赖（纯 JDK ImageIO + 内置 GIF 编码器） |
| 聊天 / 主动消息 / 情绪 / 进化 / 日记 | 本机 | 不需要额外依赖 |

也就是说：**只跑本地模式，本文可以整篇跳过。**

## 1. ffmpeg（视频关键帧）

`watch` 工具需要从视频里抽 ≤8 张 JPEG 关键帧。agentd 跑在 JRE 上，没有 Android/iOS 的
媒体框架，因此调用系统 `ffmpeg`：

- 查找顺序：`--ffmpeg <path>` → `YAP_FFMPEG` 环境变量 → `PATH` 里的 `ffmpeg`。
- 超时 30 秒；单视频 ≤64 MiB；输出≤1024px。
- **找不到解码器时不会伪造画面**，工具结果明确是：
  `服务器没有视频解码器，看不了视频`。聊天其它功能不受影响。

安装：

```bash
# macOS（本机跑服务端）
brew install ffmpeg

# Debian / Ubuntu
sudo apt-get update && sudo apt-get install -y ffmpeg

# Alpine（Docker 常见基础镜像）
apk add --no-cache ffmpeg

# 验证
ffmpeg -version
```

如果 ffmpeg 不在 PATH 或想固定版本：

```bash
agentd --data /var/lib/yap --port 8788 --ffmpeg /opt/ffmpeg/bin/ffmpeg
# 或
YAP_FFMPEG=/opt/ffmpeg/bin/ffmpeg agentd --data /var/lib/yap --port 8788
```

Dockerfile 片段：

```dockerfile
FROM eclipse-temurin:17-jre
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg fonts-noto-cjk \
    && rm -rf /var/lib/apt/lists/*
COPY agentd /opt/yap/agentd
ENTRYPOINT ["/opt/yap/agentd/bin/agentd", "--host", "0.0.0.0", "--port", "8788", "--data", "/var/lib/yap"]
```

## 2. 中文字体（sticker.make 叠字）

服务端合成表情时用 JDK 逻辑字体 `SansSerif` 画文字。JDK 会从操作系统字体目录找可显示中文的字体：

- **macOS**：系统自带苹方/黑体，通常开箱可用；
- **Windows**：系统自带微软雅黑/宋体，通常可用；
- **Linux 最小镜像 / 容器**：经常只有 DejaVu（无中文），中文会变成方块 `□□□`。

安装中文字体：

```bash
# Debian / Ubuntu
sudo apt-get install -y fonts-noto-cjk

# Alpine
apk add --no-cache font-noto-cjk

# 验证系统里有中文字体
fc-list :lang=zh | head
```

验证 JRE 真的能画中文（可选）：

```bash
cat > /tmp/CjkCheck.java <<'JAVA'
import java.awt.*;
import java.awt.image.BufferedImage;
public class CjkCheck {
  public static void main(String[] a) {
    var img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
    var g = img.createGraphics();
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 24));
    System.out.println("canDisplay(中)=" + g.getFont().canDisplay('中'));
    g.dispose();
  }
}
JAVA
java /tmp/CjkCheck.java
# 期望输出：canDisplay(中)=true
```

如果输出 `false`，`sticker.make` 的中文会变方块；装字体后重启 agentd 即可（JDK 在进程启动时
缓存字体列表）。

## 3. 启动时自检

agentd 启动日志会打印解码器是否可用（脱敏，不含 key）：

```text
  视频解码：ffmpeg 可用（/usr/bin/ffmpeg）
# 或
  视频解码：ffmpeg 不可用（服务器没有视频解码器，watch 会明确说看不了）
```

上线前跑一遍真实能力检查（需要一条带视频/音频的会话）：

```bash
curl -sS -X POST "http://127.0.0.1:8788/v1/personas/<id>/evolution/evaluate" ...  # 可选
# 更直接：在客户端发一条视频，让模型调用 watch；看工具结果是否为「看了 N 帧」而不是「看不了视频」
```

## 4. 部署检查清单

- [ ] `ffmpeg -version` 在**运行 agentd 的用户**的 PATH 里可执行（不是 root 的 PATH）；
- [ ] 如果用了 `--ffmpeg` / `YAP_FFMPEG`，路径对 agentd 进程可读、可执行；
- [ ] `fc-list :lang=zh` 有输出，或 `CjkCheck` 输出 `true`；
- [ ] 容器镜像里同时装了 `ffmpeg` 与 `fonts-noto-cjk`（缺一分别影响 watch / sticker.make）；
- [ ] 重启 agentd 后再验证一次（字体列表在进程启动时加载）；
- [ ] 不把 `--llm-key` 写进命令行历史（用 `YAP_LLM_KEY` 环境变量或 secrets 文件）。

## 5. 与客户端的关系

- 远程服务端缺 ffmpeg：只有云端 `watch` 降级，客户端本地模式仍能看视频；
- 远程服务端缺 CJK 字体：只有云端 `sticker.make` 的中文变方块，客户端本地模式正常；
- 本机（localhost）服务端：macOS 通常字体齐全，ffmpeg 仍需自行安装；
- 客户端切到服务端模式后，`watch` / `sticker.make` 的执行者是服务端，所以依赖也转移到服务端。
