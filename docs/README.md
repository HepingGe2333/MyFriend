# 文档索引

这个目录里的文件都是**写给项目使用者与贡献者**的。项目的入口在根目录：

- [README.md](../README.md) · [English](../README.en.md) — 项目是什么、特性、架构、构建与测试
- [CONTRIBUTING.md](../CONTRIBUTING.md) · [English](../CONTRIBUTING.en.md) — 环境、代码风格、提交规范、发版流程

## 目录

| 文件 | 内容 |
| --- | --- |
| [ARCHITECTURE.md](ARCHITECTURE.md) · [EN](ARCHITECTURE.en.md) | 分层、数据流、跨端契约、agent 核心的三种形态与演进路径 |
| [I18N.md](I18N.md) · [EN](I18N.en.md) | 双语方案：资源位置、新增文案规则、语言指令、守卫测试 |
| [CHANGELOG.md](CHANGELOG.md) · [EN](CHANGELOG.en.md) | 每个版本的实际变更 |
| [IOS-PARITY.md](IOS-PARITY.md) | 两端哪些必须一致、哪些受平台限制、iOS 还缺什么 |
| [performance.md](performance.md) · [iOS 章节](performance-ios.md) | 大库基准（10 万条迁移、检索分页耗时、备份 500 图内存）与结论 |
| [local-search.md](local-search.md) | 本地聊天检索用了什么框架、能搜什么、明确不支持什么、升级路线 |
| [audits/](audits/README.md) | 独立审计记录（每个版本的发现、修复与已接受的取舍） |
| [design/](design/roadmap-1.9.md) | 历史设计记录：`0.9.0` 之前各阶段的行为规格与验收表，保留供追溯，不是当前行为说明 |

## 跨端契约

不在 `docs/` 里，但在同一个仓库中：

| 目录 | 内容 |
| --- | --- |
| [spec/agent-core/](../spec/agent-core/README.md) | 决策层的行为用例（睡眠、`defer_reply`、主动上限、心跳、记忆衰减、工具参数），两端测试共读；实现是 `agentcore/` 的 Kotlin Multiplatform 代码，iOS 经 `AgentCoreKit` framework 复用 |
| [spec/messages-blocks/](../spec/messages-blocks/README.md) | 历史按 200 条一块存储、区块分页与「无候选上限」检索的存储/遍历契约 |
| [spec/web-search/](../spec/web-search/README.md) | `web_search` 的多 provider 契约（MessageAPI / Tavily / Brave / Serper / 自建）与 `web_fetch` 行为 |
| [web-search-live-check.md](web-search-live-check.md) | 各搜索 provider 的**真实接口实测**（字段形状、状态码、延迟），不含密钥 |
| [spec/search/](../spec/search/README.md) | 聊天检索的摘要/高亮与排序/过滤规则 |
| [fixtures/backup/](../fixtures/backup/README.md) | 备份 JSON 的黄金样本（v1 只读、v2 契约），两端互读 |

## 子项目文档

| 文件 | 内容 |
| --- | --- |
| [agentcore/README.md](../agentcore/README.md) | 共享决策层：依赖关系、接口、边界 |
| [agentd/README.md](../agentd/README.md) | 独立 agent 核心服务：运行方式、接口、超时与鉴权 |
| [ios/README.md](../ios/README.md) | iOS 子工程：构建、模拟器、真机安装步骤 |

## 贡献流程

新增或修改文档时：

1. 中文为默认版本；涉及使用者或贡献者的内容同时提供英文镜像（`*.en.md`）。
2. 不写「本轮」「按维护者要求」「由 AI 生成」这类过程性说明——变更历史放 `CHANGELOG.md`。
3. 不写面向工具的操作步骤（版本号该填哪几处、审计怎么发起）；这类说明属于维护者的本地工作区，不进仓库。
4. 文档里不出现本机的绝对路径、工作区目录（`.tooling/` 等）或只在本机可用的命令；构建命令统一用 `./gradlew` 与 `swift test`。
