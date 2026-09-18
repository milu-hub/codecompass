# Python 分析器：设计 + 任务拆分（R2 设计稿，未实现）

> 本文件只做设计，不含实现。按项目协议 R2：先给「接口 / 数据结构 / 关键流程」+ **最容易出错的三个点**，
> 确认后再按 R3（0.5–2 天一个任务）逐条实现，R5 以测试为验收。
> 目标语言建议 Python（理由见 §3.1），若改做 JS/TS 或 Go，本文件结构不变，只有解析器选型与角色映射两节要换。

## 0. 边界声明（先读这一节）

**本设计超出了当前 MVP 的书面边界，需要你先明确放开，否则不该开工。**

- `docs/AGENTS.md` 的「每次必加的『不要做』」第一条就是 **「不要实现 Java 以外的解析器」**，
  「MVP 支持的解析目标」也写明 **「MVP 首发只实现 Java / Spring 解析器」**、**「只解析 `**/src/main/java`」**。
- `docs/TASKBOOK.md` 的「第一版不做」清单里同样有多语言解析器的排除项。

也就是说：**产品目标**是多语言（架构按此设计，`LanguageAnalyzer` + 注册表 + 配置驱动扫描都是照着这个目标做的），
但**当前里程碑的交付范围**只有 Java。本文件做的是"下一里程碑"的设计，不改变本期范围。

放开时必须同步改两处书面边界（否则下一个人会以为越界了）：
1. `docs/AGENTS.md`：把「不要实现 Java 以外的解析器」改成「本期实现 Python 解析器；仍不实现 Python 以外的解析器」；
2. `docs/TASKBOOK.md`：在「第一版不做」里划掉对应条目，并指向本文件与后续任务编号。

---

## 1. 目标与非目标

**目标**：输入一个 Python 仓库，得到与 Java 仓库同等的体验 —— 类/函数清单、依赖图、点行看源码、可提问、
学习路线、测验、笔记、分享页，且**行号全部来自检索层**（产品的核心承诺，不允许模型编造）。

**非目标（本期明确不做）**：

- Python 2（语法分叉，收益低）
- 完整类型推断 / 跨文件符号解析（Python 动态特性决定了这里没有正确的静态答案）
- 动态导入（`importlib`、`__import__`、字符串拼 import）
- 混合仓库的跨语言依赖边（Java ↔ Python）；本期按"一门语言一份 scan 配置"处理，混仓时各扫各的
- 把现有 `PythonStubAnalyzer` 改成"看起来有数据"的假结果（stub 的价值就是诚实返回空）

## 2. 现状与差距（每条都指到文件）

**已具备的地基**（这部分不用动）：

- 语言中立模型：`CodeUnitInfo` / `MethodInfo` / `FieldInfo` / `DependencyEdge` / `FailedFile`，
  `kind` 与 `role` 都是开放字符串，枚举刻意不用（`CodeUnitInfo` 类注释写明原因）
- 派发点：`LanguageAnalyzer` 接口 + `LanguageAnalyzerRegistry`（内部零语言分支，只查表）
- 扫描：配置驱动的 `SourceFileScanner`（`scan.sources[].sourceRoot` 路径片段 + `fileExtensions`）
- 编排层语言中立：`AnalysisOrchestrator` 只依赖接口；**LLM 提示词里没有硬编码 Java/Spring**（已 grep 确认）

**四个缺口**：

| # | 缺口 | 位置 | 影响 |
|---|---|---|---|
| G1 | 扫描配置只有 java（`.java` / `src/main/java`） | `application.yml:26-32` | 非 Java 仓库扫出 0 文件 → "done + 空图" |
| G2 | `PythonStubAnalyzer` 返回 `AnalyzeResult.empty` | `analyzer/python/PythonStubAnalyzer.java` | 注册了 `python` 但永远扫不到 `.py`，形同未注册 |
| G3 | **`UnitRoleAnnotator` 是单例注入**（实现是 Spring 专用的 `CoreAnnotationClassifier`） | `AnalysisConfiguration:45`、`AnalysisOrchestrator:51/122` | 加第二门语言时**必须改业务层**，违反"新增语言只改一个实现 + 一份配置" |
| G4 | 前端语言档案只有 java | `frontend/src/utils/sourceHighlight.ts` 的 `PROFILES` | 无档案时退化为纯文本（可用，但没语法色） |

G3 是这次要顺手还掉的**架构欠账**：接口注释本身就写着"将来多语言时按语言查表即可，业务层不动"，
但当前编排层注入的是唯一 bean，做不到。**这一条不解决，Python 就得靠改业务层硬塞进去，等于把多语言架构作废。**

## 3. 设计

### 3.1 解析器选型

| 方案 | 优点 | 致命问题 |
|---|---|---|
| **ANTLR4 + grammars-v4 的 Python3 语法**（推荐） | 语法成熟、错误恢复可用、产出真正的 AST、Java 生态标准 | 新增构建期代码生成（`antlr4-maven-plugin` + runtime 依赖） |
| 手写缩进感知结构解析器 | 零新依赖，只需类/函数/import 这些结构 | 缩进/续行/f-string/嵌套定义边界多，写对很难，**行号一错整条引用链失真** |
| 调用本机 CPython `ast`（子进程 + JSON） | 最准 | 引入运行时外部依赖（机器上得有 python3），Windows/Linux/CI 不一致，安全与超时都要另做 |

**建议 ANTLR4**：本项目已用 JavaParser 解析 Java，同一条"用成熟语法库、不自己造解析器"的路线保持一致。

### 3.2 配置（`scan.sources` 追加一项）

```yaml
- language: python
  source-root: 'src'          # 待定：见 §7 决策 2
  file-extensions: ['.py']
  excluded-file-names: []     # __init__.py 不排除：它承担包语义与再导出边
```

### 3.3 数据结构映射（Python → 语言中立模型）

| 中立模型字段 | Python 的取值 |
|---|---|
| `CodeUnitInfo.id` | `repositoryId + ":" + relativePath + "#" + modulePath.ClassName`（与 Java 同构，保证 `#` 之后是人类可读限定名） |
| `kind` | `class` / `function`（模块级函数）/ `module`（可选，见决策 4） |
| `packageName` | 由 `source-root` 之后的目录序列推导（`a/b/c.py` → `a.b.c`），无 `__init__.py` 也照样推导 |
| `framework` | 识别结果：`django` / `flask` / `fastapi` / `""` |
| `annotations` | 装饰器名（`@app.route` → `app.route`），与 Java 注解同语义 |
| `fields` | 类属性 + `__init__` 里的 `self.x = ...` 赋值（后者行的归属要小心，见 §4.1） |
| `methods` | `def`，`MethodInfo.id = unitId + "#" + 签名`（含参数个数以便同名重载区分，Python 里同名靠默认参数） |
| `startLine` / `endLine` | **1-based 闭区间，必须覆盖装饰器行到块的最后一行**（§4.1） |
| `DependencyEdge` | import 图（§3.4），`kind` 沿用既有取值语义 |

### 3.4 依赖边规则（先定规则，再写代码）

| import 形式 | 目标存在时 | 目标不存在时 |
|---|---|---|
| `import a.b` | 连到 `a.b` 模块单元（若 module 是单元）或该模块内全部单元 | 丢弃（不造幽灵节点，沿用 Java 侧规则） |
| `from a.b import C` | 若 `a.b` 里有 `C` → 连到 `a.b.C`；否则连到模块 | 丢弃 |
| `from . import x` / `from ..pkg import y` | 先按当前模块所在包归一成绝对模块路径，再同上 | 丢弃 |
| `import a.b as c` | 同 `import a.b`（别名只影响后续引用，不影响边） | 丢弃 |
| `__init__.py` 里的再导出（`from .x import Y`） | 连边（这正是"包对外暴露面"） | 丢弃 |
| 条件导入（`try/except ImportError`） | 连边，但在 `FailedFile`/日志里不做特殊处理 | 丢弃 |
| 动态导入 | **不解析**（写进文档与提示） | — |

### 3.5 角色与框架（关键改造：annotator 按语言查表）

- 新增 `PythonRoleAnnotator implements UnitRoleAnnotator`（`language() = "python"`），
  把 `UnitRoleAnnotator` 改成与 `LanguageAnalyzerRegistry` 同构的**按语言查表**，
  编排层改为 `annotators.forLanguage(language)`。
- 框架标记与角色映射（放 `application.yml` 的 `analyze.python.*`，与 Java 侧同风格，可配置）：

| 框架 | 标记（示例） | 角色映射 |
|---|---|---|
| Django | `django.db.models.Model` 基类、`urls.py`、`views.py` | `model` / `entry` / `controller` |
| Flask | `@app.route`、`Flask(__name__)` | `entry` / `controller` |
| FastAPI | `@app.get/@post`、`FastAPI()`、`Depends` | `entry` / `controller` / `service` |
| 纯脚本 | `if __name__ == "__main__"` | `entry` |

- **前端角色配色表要同步**：`ClassList` 的角色 → 颜色映射与 Mermaid 的 `role_*` classDef 都要加 Python 的角色值
  （否则新角色的 tag 会掉到默认色，图和列表两边不一致）。

### 3.6 前端

- `sourceHighlight.ts` 增加 `python` 档案：`#` 行注释、`'''`/`"""` 文本块、无注解前缀
  → **装饰器 `@dec` 需要单独一档**（复用现有 `annotation` 颜色档，不新增色相）。
- 语言标签、类列表、图、分享页都已通用，不需要改；只有"角色文案/颜色表"要加条目。

### 3.7 与 LLM 层、检索层的衔接

- 提示词已是语言中立，不需要改；但**检索层的分词**要按语言走（Java 的驼峰切分对 `snake_case` 不适用），
  这一条属于 P7 的验收范围（问 Python 代码时引用行号是否准）。

## 4. 最容易出错的三个点（R2 强制）

### 4.1 缩进即作用域 → 行号范围错一行，整条链路失真

Python 没有大括号，块边界靠缩进与续行。必须处理：装饰器行归属、多行括号/反斜杠续行、
三引号字符串内出现的"伪代码"、`if TYPE_CHECKING:` 里的定义、嵌套函数。
**行号是产品的核心承诺**（引用必须能点回源码），所以 P3 的验收必须包含"人工核对范围"的样本，
不能只测"解析成功"。

### 4.2 依赖边的语义边界没定清 → "图很漂亮但是错的"

`from a.b import C` 里的 `C` 可能是类，也可能是子模块；相对导入要先归一化；
`__init__.py` 的再导出会让"同一个类"出现两个可达路径。规则必须在 §3.4 那张表里写死并逐条测，
不允许"看起来差不多"。

### 4.3 id 的确定性 → 缓存 key、可复现、点击定位全部连坐

没有 `package` 声明、namespace package（无 `__init__.py`）、同名模块分布在不同目录、
模块级函数是否单独成单元 —— 任何一条处理不当，id 就不稳定：T11 的缓存 key 会漂移、
测试无法逐字节复现、点引用定位会跳错。**id 规则一旦定下就写进测试当契约。**

## 5. 验收标准（R5：测试即验收）

1. **三个黄金样本** + 人工标注：纯脚本（单文件）、Flask 小应用、Django 小应用；每个样本给出
   人工核对的"单元集合 + 边集合 + 关键行号范围"，覆盖率按 Java 的 144/144 同口径统计。
2. **行号准确性**：抽 20 个引用逐条核对（对齐 Java 侧 20/20 的口径）。
3. **单文件解析失败不使整次分析失败**：混入一个语法错误文件，其余文件照常产出。
4. **端到端**：真实仓库（`pallets/click`、`psf/requests`）能出图、能提问、引用可点击、路线/测验/笔记/分享可用。
5. **架构回归**：Java 既有测试全绿；新增"业务层不得 import 任何语言实现类"的测试（G3 的守卫）；
   混仓仓库（Java+Python 都有）不报错、按各自配置各扫各的。
6. **诚实性回归**：非 Java 且非 Python 的仓库（Go/Rust…）仍然是明确提示，不出现"done + 空图 + 无说明"。

## 6. 任务拆分（每个 0.5–2 天，R3 粒度）

| 编号 | 任务 | 依赖 | 预估 | 验收 |
|---|---|---|---|---|
| P1 | ANTLR 接入（runtime 依赖 + 构建期生成语法 + 冒烟解析一个 `.py`） | — | 1 天 | 能解析并打印 AST 顶层节点 |
| P2 | 扫描配置 + 包名/模块推导 + id 规则 | P1 | 1 天 | id 契约测试（含 namespace package、同名模块） |
| P3 | 类/函数/方法/字段抽取 + **行号范围** | P2 | 2 天 | 黄金样本单元集合 100% + 范围人工核对 |
| P4 | 依赖边构建（§3.4 矩阵逐条） | P2 | 1.5 天 | 每条规则一个用例 + 无幽灵节点 |
| P5 | 角色/框架标注 + **annotator 按语言查表**（含 Java 侧回归） | P3 | 1.5 天 | Java 全绿 + Python 角色映射用例 + 业务层无语言依赖 |
| P6 | 前端：Python 语言档案（高亮）+ 角色配色表补条目 | P3 | 1 天 | 源码区有语法色、角色 tag 与图配色一致 |
| P7 | 黄金样本与端到端验收（覆盖率、行号 20/20、检索分词适配） | P3–P6 | 2 天 | §5 全部指标 |
| P8 | 文档回填（TASKBOOK「新增语言」章节、PROGRESS、架构说明） | P7 | 0.5 天 | 新人照文档能加第三门语言 |

合计约 11.5 天（可并行压缩到 8–9 天）。

## 7. 需要你拍板的四件事

1. **目标语言**：Python（推荐，stub 已占位、ANTLR 语法成熟）／JS-TS／Go？
2. **源码范围**：`source-root: 'src'`（覆盖 `src/` 下的所有 `.py`，但会把 `src/**/test_*.py` 也扫进来）
   还是 `'.'`（整仓，排除 `tests/` `venv/` `.venv/` `site-packages/`）？——我倾向后者 + 排除表。
3. **框架覆盖**：Django + Flask + FastAPI 三档（推荐）还是只做一档 + 纯脚本兜底？
4. **单元粒度**：模块级函数、甚至模块本身要不要成为可点击的单元？
   我倾向"模块级函数是单元（`kind=function`）、模块不是"，否则类列表会被包文件刷屏。
