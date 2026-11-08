# 第二批渲染回迁：稳定工具栏与窗格布局（2026-11-07）

用户要求：继续第二批稳定布局回迁，允许测试，审查验证后推进主分支合并；随后明确本轮只用编译和 JVM 结果合并，窗口测试另行安排。
状态：生产改动、几何及窗口回归已完成独立静态审查；生产及仪器 Kotlin 编译通过，2779 项 JVM 测试通过；已通过 `076ba353` 合入本地 master。窗口仪器与目标设备验收未运行。
本轮边界：只回迁稳定工具栏／窗格布局并保留主线 View／湿墨层替换，授权编译、JVM 测试及本地合并；按用户选择不运行窗口仪器，不生成 APK、不安装或运行模拟器，不推送或发布。

## 来源与实现

从已含第一批诊断／Surface 捕获的本地 master `cf2a4d9d` 建立 `codex/stable-pane-layout-backport`，复用独立工作树，保留原 `codex/render-lifecycle`。生产差异取自 `ed996c8c` 的三个布局文件，不整文件覆盖其他主线代码或复制旧任务断点。

- `PaneChromeLayout` 固定内容组合位置，并保留停靠工具栏槽；换边、停靠／浮动或隐藏不再迁移内容宿主。工具栏本身仍在停靠／浮动调用点间切换，浮动工具栏保持原菜单覆盖次序。
- `ToolbarFrame` 的外围布局调用上述实现，隐藏时保留内容槽及零遮挡信息。
- `MainActivity` 的窗格按 owner 与文档渲染类型标识；`PaneToolbarAround` 不再因工具栏显隐切换内容宿主结构。

真正退场／重挂仍走主线 `surfacesForAttach()`。主菜单通过停止组合 SplitHost 隐藏编辑区，返回属于真实重挂；分页保留普通 CanvasView 并替换湿墨 pad，画布保留文档／viewport／scene，但替换绘制 View 与 pad。稳定布局不承诺 Surface 或 EGL context 永不重建。

画布仍只单独打开；不恢复画布分屏。既有画布分屏拒绝、旧会话兼容、前缓冲偏好、暂存笔迹归还、旧回调隔离及首次未挂接守卫保留。本批不迁入 RenderSession、SceneStamp、DrawReceipt、保存／录音交接或 RF01 修正。

## 已完成验证

- JDK 21.0.10、Gradle 8.13、AGP 8.12.3、SDK 36；使用独立输出 `/private/tmp/xnotes-stable-pane-layout` 和项目缓存 `/private/tmp/xnotes-stable-pane-layout-cache`。
- `:app:compileDebugKotlin` 通过，日志 `tmp/stable-pane-compile.log`。
- `:app:testDebugUnitTest` 全量 247 个套件、2779 项通过，失败／错误／跳过均为 0，日志 `tmp/stable-pane-unit.log`。包括 PaneChromeLayout 3、RenderDiagnostics 11、SurfaceCapture 10、InfiniteSurfaceAttach 4 项；不是沿用历史运行结果。
- 三生产文件及 3 项几何测试独立静态复核无必修项。GL、两种 Editor 与第一批诊断／捕获实现相对基线未改变；两个 AndroidView factory 的主线替换调用保留。
- 生产／几何验证切片保存为 `b3063ee2`。新增 2 项 PaneChromeHostTest 与 8 项 SplitHostLifecycleTest，Runner 保留全部原注册；窗口源码已独立静态复核，`:app:compileDebugAndroidTestKotlin` 通过。初次编译发现 PdfDocument 不实现 Closeable，测试夹具改用显式 finally.close 后重编通过；日志 `tmp/stable-pane-android-compile.log`、`tmp/stable-pane-android-compile-retry.log`。

## 另行安排的窗口验证

已迁移历史真实窗口夹具，按当前产品范围覆盖单画布四边／浮动／停靠／输入、通用内容槽工具栏显隐，分页笔记与 PDF 的分屏、横纵方向、比例、交换、关闭任一侧及首页恢复。真正退出宿主再返回应按主线规则替换 View／pad，不以“任何操作都不重挂”为验收目标。第一批 Surface 捕获及既有仪器注册继续保留。

窗口输入用例通过 sendPointerSync 注入并核对 screen→content/page 坐标与实际目标文档；HOLD／SELECT 用例直接经过真实 View.dispatch，覆盖 HOLD 跨布局、SELECT 正常抬笔／取消，不声称已覆盖待提交 SELECT 跨布局。整体隐藏／显示 SplitHost 复用主菜单的组合边界，验证主线真实替换，不是 MainActivity 菜单按钮端到端验收。夹具使用独立本地目录并恢复全局引用，取消／等待相关 worker 后才删除文件。

失败清理复核另外补齐：首次挂载前已启动的 GLThread 经现有容器真实 add/remove 退出；disposeComposition 抛出时仍执行 activity.finish。两处增量独立复核通过，最终仪器编译通过，日志 `tmp/stable-pane-android-compile-final.log`。真实重挂用例验证 View／pad 替换及编辑状态，不把布局帧等待当成新 EGL context／GL 绘制帧或像素验收。

软件与目标设备证据分开：模拟器即使通过，也不能代表 MovinkPad Android 15 或用户天玑 8350／Android 16 的 GPU 显示、笔延迟与原黑块效果。窗口执行与目标设备验收另行安排。

## 合并准备

- 源码保存点：生产／几何 `b3063ee2`，窗口测试与失败清理 `c949a778`；之后只更新授权与结果记录。
- 主线由 `cf2a4d9d` 前进到 `b9beeb77`，仅新增第一批诊断合并后审查及任务记录，未改应用／测试源码。合并须保留该记录及 D-1 至 D-7 待修项，不在本批顺带修正，也不将 RF01 四项原分支问题标记完成。
- 用户接受本轮仅用编译与 JVM 结果合并；未运行的窗口与目标 GPU 验收继续单独列出。

## 本地合并结果

- `codex/stable-pane-layout-backport` 的 `b3063ee2`、`c949a778` 及授权记录 `4d43edf3` 通过 `076ba353` 以 `--no-ff` 合入本地 master，合并前为 `b9beeb77`、工作树干净，无冲突。
- 合并后应用／测试源码和构建配置与已验证回迁分支完全一致；主线原有诊断审查两文档及 tasks/todo 原样保留。独立增量复核无新增必修项，`git diff --check` 通过；没有因仅文档差异重复运行测试。
- 保留 `codex/render-lifecycle` 与两个回迁分支；本批不反向合入原重构分支，不启动 RF01 修正或后续批次。
- 没有生成 APK、启动模拟器、安装、推送或发布。原黑块效果未获真机验证。

## 合并后审查

合并后审查待修项 P-1～P-6（未修复）见 [render-backport-review-followups-2026-11-07.md](render-backport-review-followups-2026-11-07.md)。
