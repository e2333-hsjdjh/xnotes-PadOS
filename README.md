# XNotes for MovinkPad

面向 **Wacom MovinkPad Pro 14（Android 15）和 Pro Pen 3** 的手写笔记应用，基于 XNotes 开发。笔记保存在设备上，无需账号或云同步。

## 下载与安装

从 [GitHub Releases](https://github.com/Hshevin/xnotes-android/releases) 下载本 fork 的 **ARM64 APK**，在平板上打开安装。已安装本 fork 正式版的用户可用同签名的新版本直接覆盖升级。分支测试包使用独立包名，可与正式版并存，各自保存数据；旧 AI 和双窗格专用测试包不再纳入本地打包。当前分支测试包见[本地交付与验证记录](docs/movinkpad/branch-tests-2026-11-05.md)。

最新本地覆盖更新：[Release 91 ARM64 APK](build/xnotes/XNotes-0.9.7-movinkpad-release91-arm64.apk)，沿用正式包名与签名，包含图层、按键录入、共享 PDF 存储和侧栏更新。验证与设备验收边界见 [Release 91 记录](docs/movinkpad/release-2026-11-02.md#release-91主分支功能合并2026-11-05)，尚未发布到 GitHub。

分支测试更新：图层与按键录入已更新至 test2，可覆盖各自 test1；新增图层侧栏/缩略图及按键交接、取消、工具栏高亮修复，见[打包记录](docs/movinkpad/branch-tests-2026-11-05.md#test2远端分支更新)。

## 主要功能

- **手写笔记**：使用分页笔记或无限画布，混排手写、文字和图片。
- **PDF 批注**：导入 PDF，在页面上手写或标注文字，保留原始 PDF。
- **双窗格**：并排打开笔记、PDF 或无限画布，也可同时查看同一份分页文档的两个位置。
- **手写笔快捷键（Release 91）**：在「设置 → 输入 → 手写笔按键映射」先录入实体按键，再命名并分配工具、撤销或重做。工具支持按住临时使用或按一下切换；临时使用保持工具栏原有选中状态。旧 Pro Pen 3 配置不迁移，需重新录入。Release 90 及以前仍使用原三键设置。
- **黑白界面**：支持浅色和夜间模式，以灰阶区分界面层级。
- **可选 AI**：在设置中添加一个或多个模型供应商（各自的地址、API 密钥和模型），在输入栏按供应商切换模型，从主菜单打开聊天，或在文档截图后选择「AI」。主动发送时才会将当前对话及所选截图或 PDF 内容发送给配置的供应商；联网搜索默认关闭。

## PDF 保存与备份

当前源码将 PDF 原件统一保存在应用私有目录，日常笔记只保存引用和批注，相同原件共享一份；旧笔记首次打开时迁移。通过应用分享或导出 `.xnote` 时会重新嵌入 PDF，得到可独立打开的完整笔记包。

**备份或换设备请使用应用的分享／导出功能，不要仅复制日常 `.xnote` 文件。** 清除应用数据或卸载会删除私有 PDF 原件，即使外部文件夹仍有引用式笔记，也不能据此恢复原件。迁移失败时保留原件或恢复资料，并提示处理。

此存储调整随 Release 91 本地交付，真实 SAF 提供商和目标设备迁移验收仍待完成。详见 [单份 PDF 存储与迁移记录](docs/movinkpad/pdf-single-copy-2026-11-05.md)。

## 图层功能（Release 91）

`drawing-layers` 已合入本地 `master`，新增分页笔记、PDF 批注和无限画布的常驻图层侧栏：缩略图支持不显示、已有笔记区域、整页笔记（无限画布仅前两种）、直接拖动缩略图或名称排序（无需长按）、双击名称重命名、新增、命名、显隐、锁定、删除及移动选中内容，支持撤销恢复。旧笔记归入默认层；PDF 原文保持独立。此功能随 Release 91 本地交付，真机显示与性能验收待完成，见[图层开发与验证记录](docs/movinkpad/layers-2026-11-05.md)。

## 更多资料

- [版本更新与验证记录](docs/movinkpad/release-2026-11-02.md) · [早期开发记录](docs/movinkpad/development-history.md)
- [源码构建与测试](docs/movinkpad/server-build.md)（JDK 21） · [MovinkPad 适配与验证范围](MOVINKPAD.md)

本项目源自 [XNotes Android](https://github.com/shardulvs/xnotes-android)，沿用 [MIT 许可证](LICENSE)。

通用按键录入的实现、源码参考与验证边界见 [开发记录](docs/movinkpad/recordable-pen-shortcuts-2026-11-05.md)。

## iOS 移植

原生 SwiftUI 工程、运行说明与 Android 功能对照表见 [XNotes for iOS](ios/README.md)。当前为移植的首个功能阶段。
