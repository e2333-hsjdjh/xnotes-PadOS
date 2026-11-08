# AI 读取 PDF 改为原生工具调用（2026-11-06）

用户要求：AI 读 PDF 时失败，提示模型未返回有效的 PDF 工具请求；改成和 DSH（DeepSeek Harness）一样的做法。
状态：已实现；JVM 单元测试通过（`AiPdfToolsTest`、`AiSessionsTest` 等 `com.xnotes.ai.*`，本机 JDK 21）；真实供应商与目标设备验证待执行
本轮边界：只改源码与文档并本地提交；不打包、不安装、不推送

## 原因

旧流程在回答前单独跑一个「PDF 准备」循环：用提示词要求模型只输出一个 JSON 对象（`{"tool":"read",…}`），再用 `JSONObject(raw)` 严格解析，任何偏差都直接报 `PDF_TOOL_INVALID_REPLY` 并中断回答。常见触发：JSON 前后带说明文字、代码块不是 ```` ```json ````、思考内容写进正文（`<think>`）、模型照历史直接作答、正文为空。

## 参考

DeepSeek Harness `deepseek-ai/deepseek-harness`（MIT）提交 `fcc0ad46`：`packages/fs/tool-fs`（`read` 带 `offset` / `limit` 与「如何继续」页脚、输出上限；`read_image` 以原生图片块返回）、`docs/subsystems/attachment.md`（附件不可变、按 ID 引用、模型按需读取）。DSH 自身的 PDF 读取仍为延期项，本轮把同样的模式落到 PDF 页上，独立实现。

## 实现

- 回答模型在同一个原生工具循环中按需调用：
  - `pdf_read(attachment_id, start_page, end_page?)`：1–5 页文字，末尾页脚说明「继续请用 start_page=…」或「已到文件末尾」；无文字层时提示改看页图。
  - `pdf_search(attachment_id, query, start_page?, end_page?)`：最多 50 页、10 条结果，页脚说明检查到哪一页。
  - `pdf_view_page(attachment_id, page)`：页图，每次回答最多 4 张。Chat Completions 的 tool 消息只能是文字，因此页图在该轮全部工具结果之后以一条用户消息附上，并标为不可信资料。
- 与联网工具同时提供；工具调用按名称分发，未提供的工具（如没开联网时的 `web_search`）只回复失败、不执行。
- 参数错误、附件 ID 不符（附上可用附件列表）、页码越界、页图或字数超限都作为工具结果交还模型，由模型更正，不再中断整轮回答。单次结果最多 24000 字（截断并说明），整轮 64000 字。
- 系统提示按实际提供的工具分别加入联网与 PDF 指引；最多 8 轮工具调用，用完后在最后一个工具结果中说明并要求作答。
- 回答请求沿用会话的模型、思考深度与自定义提示词；思考模型的 `reasoning_content` 按原样交回。
- 接口因提供工具被拒（HTTP 400 / 422）时：联网关闭并照旧标注；PDF 退回旧 JSON 读取循环（`AiPdfTools.prepare`，使用 `AiConfig.forTools()`），保证不支持工具调用的模型仍能读 PDF。

## 待验证

- DeepSeek 官方及其他 OpenAI 兼容供应商上的真实工具调用、思考模式下的多轮调用、页图消息是否被接受。
- 不支持图片输入的模型调用 `pdf_view_page` 后，下一次请求可能被供应商拒绝（与旧流程相同的限制）。
- 目标设备 MovinkPad Pro 14 上的完整流程。

## 复查补充（2026-11-07）

主分支复查发现：`pdf_view_page` 的页图随下一次请求发送，DeepSeek `deepseek-chat` 等不接受图片输入的模型会以 400 拒绝；此时已有工具轮次，旧逻辑直接让整轮回答失败。现改为：带页图的请求被拒（`AiToolsUnsupported`）且本轮尚未关闭页图时，把对话尾部的页图消息替换为「页图未能发送」说明，`Reader.imagesOff` 后 `pdf_view_page` 只回该说明，模型改用文字继续作答；只重试一次，其他原因的拒绝仍按原样结束本轮。新增 `AiSessionsTest.aModelWithoutPictureInputGoesOnInTextWhenPageImagesAreRefused`。
