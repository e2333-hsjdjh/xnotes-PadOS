# OpenAI 官方接口返回 HTTP 400（2026-11-07）

用户要求：使用 OpenAI 官方 API Key、模型 gpt6.1terra（用户原话）时请求返回 HTTP 400，排查并修复。
状态：已实现；JVM 单元测试通过（新增 `AiHttpErrorTest`，`com.xnotes.ai.*` 与全量单元测试，本机 JDK 21）；未用真实 OpenAI 密钥验证，待用户在设备上复测
本轮边界：只改源码与文档并本地提交；不合并、不打包、不安装、不推送

## 原因（推断）

- 请求体对所有供应商都发送 `max_tokens`。OpenAI 官方接口已改用 `max_completion_tokens`，其推理系列模型（o 系列、GPT-5 及之后）对 `max_tokens` 直接返回 400「Unsupported parameter」。模型名不存在时 OpenAI 通常返回 404，因此 400 更可能是请求参数问题。
- 应用此前只显示状态码、丢弃错误体，用户无法看到 OpenAI 给出的具体原因。
- 开启联网或附有 PDF 时，首个带工具的请求被 400 拒绝后会按「不支持工具调用」退回无工具请求；若 400 的真实原因是其他参数，第二次请求同样失败。

没有拿到用户请求的原始错误体，以上为依据代码与 OpenAI 公开行为的推断。

## 修改

- `AiProtocol.tokenField`：主机为 `api.openai.com` 时发送 `max_completion_tokens`，其他 OpenAI 兼容供应商（DeepSeek、OpenRouter、自建等）仍发 `max_tokens`，避免它们不认识新字段。
- `AiProtocol.providerError` / `AiConnection.httpFailure`：非 2xx 时最多读取 64 KiB 错误体，只取 OpenAI 风格 JSON 的 `error.message`（或 `error` / `message` 字符串），压成一行、限 300 字，并把用户的 API Key 替换为 `***`；HTML 等非 JSON 内容不显示。提示为「请求失败（HTTP 400）：<供应商原因>」，工具被拒（`AiToolsUnsupported`）同样携带原因。新增字符串 `ai_msg_request_http_failed_detail`（英文与两份简体中文）。

## 待验证

- 用户用 OpenAI 官方密钥与该模型复测；若仍失败，界面会显示 OpenAI 的具体原因（例如 `reasoning_effort` 取值、图片输入或工具相关），据此继续处理。
- Azure OpenAI 等其他托管 OpenAI 模型的地址未改用新字段。
