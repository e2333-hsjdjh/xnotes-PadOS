# iOS OpenAI 请求与供应商错误（2026-11-08）

用户要求：对照最新 Android master 的新增功能继续移植，每功能独立提交上传 master。
状态：已实现；完整 Xcode 模拟器构建与 iPadOS 27.0 原生回归通过，真实供应商待验。
本轮边界：只修改 iOS 聊天请求、错误显示和测试，正常推送 xnotes-PadOS/master；不使用真实密钥或外部供应商、不打包 APK、不安装真机、不发布 Release。

官方 api.openai.com 使用 max_completion_tokens，其他兼容接口保持 max_tokens；比较完整主机名，不把子域或带后缀的相似域当官方地址。请求只使用当前供应商聊天列表内的模型，拒绝把仅列在生图组中的模型发往聊天接口。

HTTP 失败最多读取 64 KiB，解析 OpenAI 风格 JSON 的 error.message、字符串 error 或字符串 message。仅接受真实字符串；先遮蔽密钥，再合并空白和控制字符，最多显示 300 字符。HTML、无效 JSON 和过大响应只保留 HTTP 状态；读错误正文失败仍保留已知状态，取消则继续传播。不会因错误自动换供应商或重发请求。思考字段回退、SSE 内供应商错误详情、图像接口仍待后续阶段。

回归覆盖官方与代理／相似域 token 字段、嵌套与顶层错误、非字符串拒绝、短密钥及常规密钥遮蔽、中文长度限制、超大错误、HTTP 实际传输、单次请求与聊天路由校验。现有 SSE、会话保存、取消和后台生成回归同时运行。入口 ios/scripts/test-ai-settings.sh；日志 tmp/ios/http-compat-tests.log 与 http-compat-build.log。
