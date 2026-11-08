# 第三批渲染回迁：光晕缓存随 GPU 缓冲重建失效（2026-11-07）

用户要求：继续整理下一批回迁补丁，保留主线 View／湿墨层替换保护，仔细审查并通过编译和 JVM 测试后再合并；窗口测试另行安排。
状态：独立静态审查完成；生产及仪器源码编译通过，全量 2784 项 JVM 测试通过；已通过 `798bb008` 合入本地 master。EGL、窗口及目标 GPU 验收未执行。
本轮边界：仅回迁光晕缓存对 context／缓冲重建的失效处理，执行源码编译、JVM 测试及本地合并；不运行 EGL／窗口仪器，不打包、安装、推送或发布。

## 来源与范围

从已含前两批回迁的本地 master `ac70658e` 建立 `codex/halo-cache-backport`，复用独立工作树，保留原渲染及前两批回迁分支。合并前主线更新至 `eed318ac`（Release 98 版本／记录），已通过 `6840fd30` 同步到回迁分支；仅授权历史的追加冲突，两侧记录均保留。

选择历史 `8fb67676` 中的 `CanvasScene.kt` 与 `GlowTarget.kt` 小补丁，不整文件复制原重构分支。主线现状是：context 重建会清空 GPU 缓冲，但 CPU 上的 haloKey／liftHaloKey 仍在；尺寸 A→B→A 若中间在光晕绘制前返回，也可能让新缓冲被旧键误认为已有内容。本批在 context 变化或缓冲实际重建时撤销这两个键及缓存绘制姿态，保留实际 liftAt、CPU 场景、文档、视口和编辑状态；同一取整后的缓冲尺寸继续复用。

不迁入 RenderSession、SceneStamp、DrawOutcome、新异常传播或保存交接；主线的两种 surfacesForAttach、View／湿墨层替换和画布独立模式保持原样。GlowTarget.resize 返回值仅表示缓存内容被丢弃，不代表 GPU 分配／FBO 校验成功。

RF01-F4 的「原位绘制中途失败后仍保留旧键」属于另一路，与本批的 context／缓冲销毁不同；本批不宣称修复该项。原分支 RF01-F1～F4、主线诊断 D-1～D-7 仍待处理。

## 验证安排

- JVM 回归直接调用 CanvasScene／GlowTarget 的实际生命周期入口，用受控 GL 替身检查缓存失效、未重建时复用、CPU 场景及拖动状态保留。GL 替身不能证明真实像素或驱动行为。
- 迁移历史 3 项真实 EGL pbuffer 测试源码并注册 Runner，覆盖 context 重建、尺寸往返与 resize 返回协议；本轮只编译，不执行，不代表窗口 Surface 或目标 GPU 验收。
- 使用 JDK 21、独立输出 `/private/tmp/xnotes-halo-backport` 与项目缓存 `/private/tmp/xnotes-halo-backport-cache`。实际测试结果、独立审查和合并证据在下文补齐。

## 实际验证与审查

- 环境：JDK 21.0.10、Gradle 8.13、AGP 8.12.3、SDK 36；应用及测试配置与对应主线基线一致。后续同步保留主线的 versionCode 98，不自行更改安装身份或版本。
- 新增 `CanvasHaloInvalidationTest` 5 项，通过实际 scene draw 建立两种光晕缓存，再验证正常／shader 创建失败的 context 变化、ink 缺失时的尺寸往返、取整尺寸相同的复用，以及 GlowTarget 外来代次／释放后的重建。确认 CPU records、几何计数、镜像对象及实际拖动状态保留，不用新 helper 代替生产入口。
- 未应用生产补丁时，5 项中 3 项在“旧缓存键应为空”断言失败、2 项复用检查通过；应用补丁后 5 项全部通过。首次测试夹具编译发现 Stroke 构造参数不匹配，已按主线将颜色移至 ToolConfig.rgba，并设为直线以避免输入平滑缩短测试线段。初次编译失败不是缺陷复现；实际反例日志为 `tmp/halo-backport-red-retry.log`，原始失败 XML 保存为 `tmp/halo-backport-red-results.xml`。
- `:app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest` 通过。XML 汇总为 248 个套件、2784 项，失败／错误／跳过均为 0；日志 `tmp/halo-backport-validation.log`。包含前两批已有 JVM 测试，不借用历史运行结果。
- 迁入的 3 项 `CanvasHaloLifecycleTest` 来自 `8fb67676`，只调整上述笔画构造及直线夹具；Runner 保留全部原注册。尺寸往返用例在固定 pbuffer 上重建离屏光晕 FBO，不等同于窗口或 EGL Surface 真实 resize。源码编译通过，未执行像素断言。
- 两位审查者分别复核生产变化、JVM／EGL 夹具和资源清理，最终无必修项；主审复核测试失败位置、完整测试结果及主线保护。生产差异严格为两个文件的 17 行新增、5 行删除，MainActivity、两种 Editor、InfiniteCanvasView、GlWetPad、GlRenderer、GeometryStore 与基线完全一致。
- 源码验证只证明缓存失效逻辑及编译兼容；不证明真实 GPU 分配、FBO 完整性、像素恢复、原黑块修复或笔延迟改善。
- 生产补丁与 5 项 JVM 回归保存为 `5c662654`；EGL 源码及本文随后单独保存。
- EGL 源码与初次证据保存为 `b25ca951`。同步最新主线后再次调用相同三项 Gradle 任务，结果成功；应用／测试源码未变，编译与 JVM 任务为 UP-TO-DATE，沿用上述实际运行结果，未冒称第二次执行 2784 项。日志 `tmp/halo-backport-current-master-validation.log`。Release 98 构建配置及历史交付日志与 `eed318ac` 完全一致，本轮没有执行历史记录中的打包或推送动作。

## 本地合并结果

- 回迁分支保存生产／JVM `5c662654`、EGL／记录 `b25ca951`，同步主线 `6840fd30`，补充复验记录 `e39a4441`；通过 `798bb008` 以 `--no-ff` 合入本地 master。合并前主线为 `eed318ac`、工作树干净，最终合并无冲突。
- 主线同步和最终增量经独立复核，无新增必修项；合并后应用／测试源码及构建配置与已验证回迁分支完全一致，`git diff --check` 通过。Release 98、前两批回迁、诊断审查及授权记录保留。
- 保留原渲染分支及三条回迁分支，本批不反向合入原重构分支；RF01 四项及诊断 D-1～D-7 未在本轮修正。
- 本轮未生成 APK、运行 EGL／窗口仪器、启动模拟器、安装、推送或发布。目标 GPU 与原黑块效果待真机验证。
