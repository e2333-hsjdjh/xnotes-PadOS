# 不支持思考参数的模型自动降级（2026-11-07）

用户要求：GPT-4o 这类旧模型在选择了思考深度时报「思考参数不支持」错误，需要处理。
状态：已实现；JVM 单元测试通过（新增 `AiThinkingFallbackTest`、`AiSessionsTest.anAnswerSentWithoutTheChosenThinkingLevelSaysSo`，全量单元测试，本机 JDK 21）；未用真实 OpenAI 密钥验证
本轮边界：只改源码与文档并本地提交；不合并、不打包、不安装、不推送

## 原因

选择思考深度（含「关闭」）时，请求按供应商格式写入思考字段：OpenAI 官方为 `reasoning_effort`（「关闭」写 `"none"`）。GPT-4o 等非推理模型不认这个参数，返回 HTTP 400（如 `Unrecognized request argument supplied: reasoning_effort`）。只有「跟随模型默认」不发送该字段。按模型名单判断不可靠（新模型名称不断出现），因此改为按供应商的拒绝原因自适应。

## 行为

- 请求被 400 / 422 拒绝且供应商错误信息提到本次所用的思考字段（OpenAI `reasoning_effort`、OpenRouter `reasoning`、DeepSeek `thinking`、通义 `enable_thinking`）时，去掉该字段重发一次；此时尚未收到任何回答内容。这一判断先于「不支持工具调用」的判断，避免把思考参数问题误当成工具问题而关掉联网 / PDF 工具。
- 本次运行内记住「接口地址 + 模型 + 思考档位」被拒，之后同一组合直接不带该字段发送，不再先失败一次；换档位或换模型仍按所选发送。只存于内存，应用重启后重新探测。
- 回答完成后在该轮标注「当前模型不支持所选的思考设置，本次按模型默认回答」，不静默降级。
- 其他原因的 400、未选择思考档位的请求不重试，仍显示供应商原因。

## 待验证

- 真实 OpenAI GPT-4o、GPT-4.1 等非推理模型，以及 OpenRouter / 通义等供应商的实际拒绝文案是否包含字段名。
