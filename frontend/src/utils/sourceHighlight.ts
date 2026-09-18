/**
 * 源码着色（**仅用于展示**，绝不参与行号、引用、检索等任何语义）。
 *
 * 三条硬约束：
 *
 * 1. **零文本损失**：一行切出的 token 文本按顺序拼接，必须与该行原文本逐字符相同。
 *    这是本模块唯一的硬不变量 —— 着色只允许改变"颜色"，不允许增删改任何字符。
 * 2. 跨行状态（斜杠星号块注释、`"""` 文本块）必须整段源码一次算完，不能逐行独立算，
 *    否则注释和字符串会串色到后面的代码行。写这个文件时自己就踩了一次：注释里直接写
 *    出块注释的结束符，会把 JSDoc 提前闭合掉。
 * 3. 语言中立：按 language 取「行语法档案」，未知语言一律退化成纯文本（R9 中立，
 *    以后加语言只是往 PROFILES 里加一条，不动调用方）。
 *
 * 本步只上 5 类颜色（注解 / 类型 / 方法 / 字符串 / 注释），关键字故意保持正文色，
 * 不再引入第 6 种色相。
 */

/** token 类型 = 颜色档位。 */
export type SourceTokenKind = 'plain' | 'annotation' | 'type' | 'method' | 'string' | 'comment'

export interface SourceToken {
  text: string
  kind: SourceTokenKind
}

interface LineSyntaxProfile {
  /** 保留字：命中一律按正文色，不再判方法/类型 */
  keywords: ReadonlySet<string>
  lineComment: string
  blockCommentStart: string
  blockCommentEnd: string
  /** 注解前缀，如 Java 的 '@'；没有注解语法的语言传 null */
  annotationPrefix: string | null
  /** 文本块分隔符，如 Java 的 '"""'；没有则 null */
  textBlockDelimiter: string | null
}

const JAVA_KEYWORDS = new Set([
  'abstract', 'assert', 'boolean', 'break', 'byte', 'case', 'catch', 'char', 'class', 'const',
  'continue', 'default', 'do', 'double', 'else', 'enum', 'extends', 'false', 'final', 'finally',
  'float', 'for', 'goto', 'if', 'implements', 'import', 'instanceof', 'int', 'interface', 'long',
  'native', 'new', 'non-sealed', 'null', 'package', 'permits', 'private', 'protected', 'public',
  'record', 'return', 'sealed', 'short', 'static', 'strictfp', 'super', 'switch', 'synchronized',
  'this', 'throw', 'throws', 'transient', 'true', 'try', 'var', 'void', 'volatile', 'while', 'yield',
])

/** 语言 → 行语法档案。加语言只加条目。 */
const PROFILES: Record<string, LineSyntaxProfile> = {
  java: {
    keywords: JAVA_KEYWORDS,
    lineComment: '//',
    blockCommentStart: '/*',
    blockCommentEnd: '*/',
    annotationPrefix: '@',
    textBlockDelimiter: '"""',
  },
}

interface ScanState {
  inBlockComment: boolean
  inTextBlock: boolean
}

function isIdentifierStart(char: string | undefined): boolean {
  return !!char && /[A-Za-z_$]/.test(char)
}

function isIdentifierPart(char: string | undefined): boolean {
  return !!char && /[A-Za-z0-9_$]/.test(char)
}

/** 从 end 起跳过空白后的第一个字符（用于判断标识符后面是不是 '('）。 */
function nextNonSpace(line: string, end: number): string {
  let i = end
  while (i < line.length && (line[i] === ' ' || line[i] === '\t')) {
    i++
  }
  return line[i] ?? ''
}

/** 字符串/字符字面量的结束位置（返回闭引号之后的下标；未闭合则到行尾）。 */
function stringEnd(line: string, start: number, quote: string): number {
  let i = start + 1
  while (i < line.length) {
    if (line[i] === '\\') {
      i += 2
      continue
    }
    if (line[i] === quote) {
      return i + 1
    }
    i++
  }
  return line.length
}

/**
 * 标识符归类。
 * - 大写开头且不是「ALL_CAPS 常量」→ 类型名（单字母如 T/E/K/V 也算类型参数）
 * - 后面紧跟 '(' 的小写标识符 → 方法
 * 都是启发式，只影响观感；判错的代价是颜色，不会动到文本。
 */
function classifyIdentifier(
  word: string,
  line: string,
  end: number,
  profile: LineSyntaxProfile,
): SourceTokenKind {
  if (profile.keywords.has(word)) {
    return 'plain'
  }
  const startsUpper = /^[A-Z]/.test(word)
  const allCapsConstant = word.length > 1 && /^[A-Z0-9_]+$/.test(word)
  if (startsUpper && !allCapsConstant) {
    return 'type'
  }
  if (nextNonSpace(line, end) === '(') {
    return 'method'
  }
  return 'plain'
}

function tokenizeLine(
  line: string,
  profile: LineSyntaxProfile,
  state: ScanState,
): SourceToken[] {
  const tokens: SourceToken[] = []
  // 连续正文攒成一个 token，避免每字符一个 span
  let plain = ''
  const flushPlain = () => {
    if (plain) {
      tokens.push({ text: plain, kind: 'plain' })
      plain = ''
    }
  }
  const push = (text: string, kind: SourceTokenKind) => {
    flushPlain()
    if (text) {
      tokens.push({ text, kind })
    }
  }

  let i = 0
  while (i < line.length) {
    // 1) 块注释续行
    if (state.inBlockComment) {
      const end = line.indexOf(profile.blockCommentEnd, i)
      if (end === -1) {
        push(line.slice(i), 'comment')
        return tokens
      }
      push(line.slice(i, end + profile.blockCommentEnd.length), 'comment')
      i = end + profile.blockCommentEnd.length
      state.inBlockComment = false
      continue
    }

    // 2) 文本块续行
    if (state.inTextBlock && profile.textBlockDelimiter) {
      const end = line.indexOf(profile.textBlockDelimiter, i)
      if (end === -1) {
        push(line.slice(i), 'string')
        return tokens
      }
      const after = end + profile.textBlockDelimiter.length
      push(line.slice(i, after), 'string')
      i = after
      state.inTextBlock = false
      continue
    }

    const rest = line.slice(i)

    // 3) 行注释：后面整行都是注释
    if (rest.startsWith(profile.lineComment)) {
      push(rest, 'comment')
      return tokens
    }

    // 4) 块注释开头（同行闭合由下一轮的第 1 分支收尾）
    if (rest.startsWith(profile.blockCommentStart)) {
      push(profile.blockCommentStart, 'comment')
      i += profile.blockCommentStart.length
      state.inBlockComment = true
      continue
    }

    // 5) 文本块开头
    if (profile.textBlockDelimiter && rest.startsWith(profile.textBlockDelimiter)) {
      push(profile.textBlockDelimiter, 'string')
      i += profile.textBlockDelimiter.length
      state.inTextBlock = true
      continue
    }

    // 6) 字符串 / 字符字面量
    if (rest[0] === '"' || rest[0] === "'") {
      const end = stringEnd(line, i, rest[0])
      push(line.slice(i, end), 'string')
      i = end
      continue
    }

    // 7) 注解
    if (
      profile.annotationPrefix &&
      rest[0] === profile.annotationPrefix &&
      isIdentifierStart(line[i + 1])
    ) {
      let j = i + 1
      while (j < line.length && isIdentifierPart(line[j])) {
        j++
      }
      push(line.slice(i, j), 'annotation')
      i = j
      continue
    }

    // 8) 标识符
    if (isIdentifierStart(rest[0])) {
      let j = i
      while (j < line.length && isIdentifierPart(line[j])) {
        j++
      }
      const word = line.slice(i, j)
      push(word, classifyIdentifier(word, line, j, profile))
      i = j
      continue
    }

    // 9) 其余字符（空白、标点、数字）走正文色
    plain += line[i]
    i++
  }

  flushPlain()
  return tokens
}

/**
 * 把整段源码切成 token 矩阵（与 lines 一一对应）。
 * 未知语言返回纯文本 token，调用方无需分支。
 */
export function highlightSource(language: string, lines: string[]): SourceToken[][] {
  const profile = PROFILES[(language ?? '').toLowerCase()]
  if (!profile) {
    return lines.map((line) => (line ? [{ text: line, kind: 'plain' as const }] : []))
  }
  const state: ScanState = { inBlockComment: false, inTextBlock: false }
  return lines.map((line) => tokenizeLine(line, profile, state))
}
