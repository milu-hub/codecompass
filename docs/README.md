# CodeCompass 文档索引与使用方式

> 本目录为仓库根下的 `docs/`，以下所有文件均位于 `docs/` 目录。`CODEX_CONTEXT.md` 中 `docs/XXX.md` 形式的引用即指向本目录中的文件。

## 文件清单
| 文件 | 内容 |
|---|---|
| `PROJECT_BACKGROUND.md` | 项目背景：定位、痛点、六大能力、技术栈区分 |
| `TASKBOOK.md` | MVP 任务书 v0.3（**唯一需求来源**） |
| `AGENTS.md` | dsh 协作规则：提问节奏、模板、"不要做"清单 |
| `TASKS.md` | 任务卡 T0～T12，粒度 0.5～2 天 |
| `SCHEMA.md` | 核心数据模型（语言中立 DTO） |
| `CODEX_CONTEXT.md` | 约 400 tokens 的固定上下文 |

## 使用方式
| 场景 | 发什么 | 约 tokens |
|---|---|---|
| 每次开工固定上下文 | `CODEX_CONTEXT.md` | 400 |
| 首次对话（复述边界） | `CODEX_CONTEXT.md` + `TASKBOOK.md` | 2.5k |
| 做某个任务 | `AGENTS.md` + `TASKS.md` 中该任务一节 | 1k |
| 查数据模型 | `SCHEMA.md` | 800 |
| 全量对齐（偶尔） | 全部 | 5k |
