package com.aria.conversation.application.service.payload;

/**
 * AI 回复 token 的 SSE payload（对应无 event: 字段的默认 data 事件）。
 *
 * <p>采用 JSON 信封而非裸字符串传输，理由：
 * <ul>
 *   <li>WHATWG SSE 规范要求 {@code data:} 字段的一个前导空格必须剥离，
 *       LLM 分词器输出的 token 天然带前导空格（例如 " 🔴 "、" 26"），
 *       裸字符串传输会导致 {@code "### 🔴"} 拼成 {@code "###🔴"} —— Markdown 标题识别失败。</li>
 *   <li>token 内可能出现换行符（例如表格分隔、段落边界），Spring 的 {@code ServerSentEventHttpMessageWriter}
 *       会把 {@code "\n"} 拆成多行 {@code data:}，前端若逐条 dispatch 就丢换行；
 *       JSON.stringify 把 {@code "\n"} 编码为 {@code "\\n"}，天然规避此坑。</li>
 *   <li>与 OpenAI / Azure OpenAI Chat Completion streaming 官方 wire format 保持一致，
 *       前端解析逻辑可以完全遵循 WHATWG 规范，无需业务 hack。</li>
 * </ul>
 *
 * @param content 一次 LLM partial-response 输出的 token 内容，原样保留所有空白与换行
 */
public record TokenPayload(String content) {}
