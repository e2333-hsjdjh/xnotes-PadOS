# 一次性授权与合并记录（2026-11-05 整理）

用户要求：整理 `AGENTS.md`，只保留长期规则；以下一次性授权、合并与阶段边界从原 `AGENTS.md` 原文移入，按日期排列，供追溯。
状态：仅为历史记录。各条授权只对当次有效，不约束后续工作；其中仍有效的技术规则已归入 `AGENTS.md` 对应主题。
本轮边界：文档整理，不打包、不安装、不推送。

## 2026-10-31

- 用户于 2026-10-31 最新要求本次直接在本机编译，覆盖此前“不在本机编译”的约定；使用 JDK 21，遵守执行环境权限。

## 2026-11-02

- 用户于 2026-11-02 确认当前分屏版本足够，授权打包覆盖早期 XNotes 的 Release；交付包使用 `com.xnotes.movinkpad`，独立测试包仍为 `com.xnotes.movinkpad.splitpreview`。不把打包授权扩展为 GitHub 推送或商店发布。
- 2026-11-02 用户要求本轮退出分屏后的实时笔迹修复不要直接打包 APK；只修改与验证代码，后续交付构建需用户另行要求。涉及前缓冲/Surface 重挂的显示修复必须区分源码验证与目标 GPU 真机验证。
- 用户随后于 2026-11-02 明确授权将上述修改打包并发布 APK，覆盖本轮先不打包约定。Release 80 沿用原应用身份和签名；发布说明必须保留实时笔迹修复尚待真机确认的边界。

## 2026-11-03

- 用户于 2026-11-03 要求：每完成一个功能阶段或修复并通过相关验证后，自动提交到本地 Git，无需再次询问；推送 GitHub 仍由用户手动操作或明确要求后执行。此约定覆盖此前“未经要求不提交”的限制，不授权自动推送、打包或发布。
- 2026-11-03 用户要求合并分屏与主分支；本地 `master` 已快进合入 `codex/split-workspace`，不再维持主分支仅含分屏前功能的边界。原分屏分支保留；未经要求仍不推送。
- 2026-11-03 用户在本服务器提供原签名密钥，要求 Debug 与 Release 共用。通过忽略提交的 `keystore.properties` 配置两种构建；不要覆盖服务器默认调试密钥、提交密钥或密码。证书 SHA-256 为 `A0:45:3B:7B:E6:C0:C8:23:9E:86:16:20:86:12:C4:12:CF:50:1D:A8:89:8C:7A:04:50:CC:92:3C:8E:35:84:51`。此要求不改变各构建的包名，也不授权本轮打包或安装。
- Ask AI development (2026-11-03): do not package APKs unless requested again. PDF, paged notes and infinite canvas screenshot actions must all support Ask AI. Real provider and target-device verification remain separate from JVM tests.
- 用户于 2026-11-03 随后授权导出 AI 独立测试 APK，使用 `-PaiPreview=true`，包名 `com.xnotes.movinkpad.aipreview`，不得覆盖正式版或双窗格测试版。此授权不包含提交、推送或设备安装。
- AI 设置（2026-11-03）：……本轮不打包 APK。（技术规则已移入 `AGENTS.md`「AI → 供应商与设置」。）
- AI chat interaction (2026-11-03): …… This interaction stage is code/test only; do not package APKs without a new request.（技术规则已移入 `AGENTS.md`「AI → 草稿与附件」。）
- Document registry migration (2026-11-03, in progress): …… No APK/install/push for this stage; the user subsequently authorized local integration into master.（技术规则已移入 `AGENTS.md`「存储、PDF 与迁移」。）
- 2026-11-03 用户授权将黑白层级与字号、间距规范打包为 Release 81（本地交付，同一身份和签名）；不包括推送或 GitHub Release。Release 构建的 buildDirectory 放到 iCloud 同步目录之外（见 Release 记录），避免出现 `… 2.*` 重复文件。
- 2026-11-03：插入空白在分页文档（笔记/PDF）分屏时属于顶部共用工具；工作区有画布时移至分页文档名称栏，画布不显示。布局按打开的文档类型分配，不随焦点切换。本轮只实现与验证，不打包 APK。
- 2026-11-03：用户要求将 AI 工作树合入主工作区，保留主分支已有 UI（灰阶、字号/间距 tokens、工具栏分配）；本轮不开始 UI 大改，不打包或推送。
- 用户随后于 2026-11-03 授权导出 AI preview3 覆盖测试包：`com.xnotes.movinkpad.aipreview` / versionCode 3，沿用 preview2 签名；不覆盖正式版，不包含设备安装或推送授权。
- 用户随后于 2026-11-03 授权制作正式 Release 82：包名 `com.xnotes.movinkpad`、versionCode 82，沿用正式版历史签名，启用 R8/资源压缩且关闭 debuggable。仅本地交付，不安装、不推送或发布 GitHub Release；继续保留现有 UI。
- 2026-11-03 用户在 UI 与翻译大改后授权代码检查并构建可覆盖安装的正式 Release 83；沿用 `com.xnotes.movinkpad` 和历史签名。仅本地交付，不包含真机安装、推送或 GitHub Release。
- 2026-11-03 用户在两轮提交核对后授权打包正式 Release 84，包含 AI 设置、新会话默认、默认提示词与侧栏修复；沿用 `com.xnotes.movinkpad` 和历史签名，仅本地交付，不安装、不推送或发布 GitHub Release。
- 用户随后于 2026-11-03 授权推送 `master` 并发布 GitHub Release 84（标签 `v0.9.7-movinkpad.84`）。此授权仅限本次，后续推送和发布仍需明确要求。

## 2026-11-04

- 用户于 2026-11-04 授权本地打包正式 Release 85，包含退出 PDF 分屏的前缓冲调整及 AI 未开始对话输入区清理；沿用 `com.xnotes.movinkpad` 和历史签名，提高 versionCode 以支持覆盖安装。仅本地交付，不包含设备安装、推送或 GitHub Release；前缓冲效果及平台交互仍待目标设备验证。
- 用户随后于 2026-11-04 授权打包交付 Release 86，用于测试湿墨层生命周期候选修复；沿用正式包名和历史签名、递增 versionCode。仅本地交付，不安装用户设备、不推送或发布 GitHub Release；尚未确认候选修复消除目标设备半屏刷新现象。
- Release 86 真机反馈无改善后，用户于 2026-11-04 授权打包 Release 87，测试分页编辑器重挂时替换湿墨 View 的下一候选；沿用正式身份与签名，仅本地交付 APK，目标设备效果仍待确认，不扩大为 GitHub 发布授权。
- 用户随后于 2026-11-04 确认 Release 87 解决原 PDF 退出空分屏后的半屏实时笔迹问题。（重挂规则已移入 `AGENTS.md`「文档打开与分屏」。）
- 用户于 2026-11-04 在远端 AI 更新合并后授权本地打包 Release 88 测试；沿用正式包名及历史签名、递增 versionCode，保留 Release 87 湿墨修复。仅本地交付，不安装、不推送或发布 GitHub Release；多截图、浮窗历史及原生联网工具仍需真实供应商和目标设备验收。
- 用户随后于 2026-11-04 再次授权拉取远端并构建 Release 89，包含 AI 回收站及搜索栏更新；沿用正式身份与历史签名，仅本地交付，不安装、不推送或发布 GitHub Release。

## 2026-11-05

- 单份 PDF：此阶段不打包 APK、不安装、不推送；平台及目标设备验收仍待执行。（技术规则已移入 `AGENTS.md`「存储、PDF 与迁移」。）
- 通用按键录入：用户授权本功能新建远端分支并推送；随后于 2026-11-05 确认合入本地 master，继续不打包 APK，本次合并不包含主分支推送。（技术规则已移入 `AGENTS.md`「笔输入」。）
- 图层第一版（`drawing-layers` 开发分支）：本次仅代码验证；用户授权将 `drawing-layers` 推送远端，不授权打包、安装或发布 Release。
- 2026-11-05 用户授权将 drawing-layers 合入本地 master，包含图层侧栏、直接拖拽和缩略图范围模式；保留主分支单份 PDF、通用按键及焦点高亮逻辑。不包含打包、安装或推送授权。

### 本机交付授权与文档合并补充

- 2026-11-05 用户要求拉取远端主分支与新增功能分支，分别本地打包独立测试 APK；旧 AI/分屏专用测试包不再维护，可清理旧测试产物。各分支使用独立工作树、构建目录及 `com.xnotes.movinkpad.branchtest.<id>` 包名，保留原签名，不覆盖正式版；交付时报告包名和源码提交。保留用户未提交修改，不将不同功能分支擅自互相合并。本次不包含设备安装、推送或 GitHub Release。
- 2026-11-05 用户随后授权将当前主分支打包为正式覆盖更新 Release 90，沿用 `com.xnotes.movinkpad` 和历史签名，递增 versionCode；只本地交付，不安装、不推送、不发布 GitHub Release。图层和按键录入分支保持独立。
- 2026-11-05 用户要求再次拉取远端 master 并打包，授权本地交付 Release 91：包含已在远端主分支合入的图层、通用按键及共享 PDF 存储，沿用正式包名和签名、递增版本。不安装用户设备、不推送或发布 GitHub Release；设备与真实 SAF 迁移验收仍单独记录。
- 随后用户要求拉取远端更新并合并。本次获取 `6a93afc4`，仅合并协作规则文档重组；本地分支构建隔离与版本递增规则归入对应主题，Release 90/91 及测试包授权移入本节。未改应用代码，未打包、安装或推送。检查文档链接、冲突标记、差异空白和原有用户修改；无需重跑应用测试。README 当前功能、交付和构建入口仍准确，无需改写。
- 2026-11-05 用户授权将本地 `master` 推送远端 `origin`，包含「关于」页改为指向 `Hshevin/xnotes-android`（Issues、Releases、许可证，上游署名），以及此前未推送的 PDF 重新关联与无限画布黑屏调查记录。仅本次推送，不包含打包、安装或 GitHub Release。

## 2026-11-06

- 用户于 2026-11-06 要求推送远端：推送本地 `master`（含关闭 / 切换文档误删私有 PDF 原件的修复、重新关联与仅笔迹副本）到 `origin/master`。此授权仅限本次，不包含打包 APK、安装或 GitHub Release。
- 用户随后于 2026-11-06 提供本机构建的 Release 95 APK 并要求发布：补提交 versionCode 95，推送 `master` 与标签 `v0.9.8-movinkpad.95`，发布 GitHub Release（附 APK 与 SHA256SUMS.txt）。仅限本次，不包含设备安装。

## 2026-11-08：iOS 开发分支按功能上传

用户先要求功能完成后在 GitHub 分支提交，随后澄清为「在一个分支上不同的提交」，并再次要求「实现一个功能就上传一次云端」。本移植任务统一使用 `codex/ios-port`，每项完成且验证通过的功能独立提交并推送。本轮为 AI Markdown 基础块排版，允许创建该远端分支并上传提交及其既有 iOS 基线祖先；不合并其他分支，不发布 Release、不打包 APK、不安装真机、不修改签名或 Git 配置。

本任务继续按上述同分支、逐功能提交 / 上传要求推进：2026-11-08 本轮功能为原生 Markdown 嵌套结构。上一轮推送已被 GitHub 拒绝（当前连接账号无目标仓库写权限）；未通过改远端或切换账号来规避。完成本地提交后检查权限，具备写权限才推送。

## 2026-11-08：iOS 云端目标改为 xnotes-PadOS/main

用户明确提供 `https://github.com/e2333-hsjdjh/xnotes-PadOS.git`，要求推送到 `main`。此前 Hshevin/xnotes-android 的权限阻碍不再作为此任务上传目标。目标仓库确认当前账号具有 push 权限；检查时仅有 master、没有 main，本轮允许创建 main 并推送当前 iOS 功能提交及依赖的既有迁移历史。保留远端 master，不改默认分支，不改本地 origin，不强制推送。后续同一移植任务每个功能独立提交并上传到这个 main，取代此前 codex/ios-port 远端命名约定。
