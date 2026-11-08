# 第五批渲染回迁：完整场景批次发布（2026-11-07）

用户要求：继续第五批回迁，保留主线 View／湿墨层替换保护，独立审查并通过编译和 JVM 测试后合并；窗口测试另行安排。
状态：独立静态审查完成；生产与仪器源码编译通过，全量 2817 项 JVM 测试通过；已通过 `cb8d1e61` 合入本地 master。EGL／窗口及目标 GPU 验收未执行。
本轮边界：仅回迁完整场景批次发布及 SVG 提交后的调度，执行源码编译、JVM 测试及本地合并；不运行 EGL／窗口仪器，不打包、安装、推送或发布。

## 来源与范围

基于已含前四批的本地 master `175785ce` 创建 `codex/scene-batch-backport`，复用独立工作树，保留原分支。选择历史 `1e80c6f5` 的 SceneUpdateQueue、CanvasScene、InfiniteEditor 三文件小补丁，使用第四批已有 ImageDrawSnapshot，不整文件迁入原重构分支。

- 一个成功批次作为一条完整队列消息发布。嵌套批次共享外层暂存；嵌套失败即使被调用者捕获，外层也拒绝发布。准备时禁止重入，失败后队列仍能接受后续批次；消费者可以读取此前已提交内容。
- CanvasScene 的 upsert 与三个湿墨 parts 入口复制列表容器；MeshData 数组继续遵守生成后不修改的移交约定，不宣称深复制。容量预留按 Reset 分段、在各段首个 upsert 前执行，保持编辑顺序。
- rebuildScene 的 reset／items／order 作为一个批次发布。SVG 请求先冻结源文件和绘制参数，批次成功发布后才撤销旧任务资格并登记／启动新任务；提交后的调度拒绝只结清该请求，不把已提交批次冒充回滚。结果接收保留主线 mainHandler.post，不依赖被替换的 View。
- 主线 View／湿墨层替换、前四批快照及光晕保护保留。本批只保证生产端完整发布，不保证模型回滚、CPU 消费半应用失败恢复或场景与帧版本配对，不迁 Session／SceneStamp／DrawReceipt／保存交接。

## 验证安排与保留事项

以真实 CanvasScene CPU 队列回归验证旧代码的部分发布、parts 列表别名和 Reset 容量问题；迁入历史 14 项队列 JVM 与 3 项 EGL 测试增量。EGL 仅编译，CPU 断言不能证明像素或目标 GPU 行为；通用 afterCommit 回调测试不代替真实编辑器 SVG worker／main 接收运行验证。

使用 JDK 21、独立输出 `/private/tmp/xnotes-scene-batch` 与项目缓存 `/private/tmp/xnotes-scene-batch-cache`。实际结果和独立复核在下文记录。

主线新增的 P-1～P-6、S-1～S-6、U-1～U-4 审查／冒烟记录完整保留。本批已修正 S-5 的 SVG worker 可变源文件读取路径，实际 SVG 异步运行仍待验证；不顺带处理其他清单。RF01-F1～F4、诊断 D-1～D-7 和既有窗口 UID／分屏夹具待重跑事项继续保留。

## 实际验证与独立审查

- 使用 JDK 21.0.10、Gradle 8.13、AGP 8.12.3、SDK 36。构建配置、包名、versionCode 及签名配置与基线一致。
- 先在未修改生产代码时运行 4 项新写的真实 CanvasScene CPU 回归，全部按行为断言失败：失败 Reset 清掉旧 Record、捕获嵌套异常仍提交、清空调用方 parts 后无顶点，以及单次 Reset 把原本 8192 顶点容量误扩至 13500；不是接口或编译失败。日志 `tmp/scene-batch-red.log`，失败 XML `tmp/scene-batch-red-results.xml`。
- 修正后相同 4 项通过，覆盖直接／嵌套失败保留旧场景及后续可用、四种 parts 入口容器所有权、单次／两段／连续 Reset 的真实 allocator 容量、最终 Record／z／activeLayerEnd。测试反射调用实际 CPU 消费入口，不创建 GL context，不用模拟像素作证据。
- 迁入历史 14 项 SceneUpdateQueue JVM 回归：成功批次完整入队、并发消费者不能见到暂存部分但能读旧消息、嵌套失败与准备失败、生产线程约束、准备阶段重入、容器复制、空批次和提交回调的时序／失败。共新增 18 项 JVM。
- `:app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest` 一次执行通过；全量 **253 套件、2817 项**，失败／错误／跳过均为 0，包含前四批 JVM。日志 `tmp/scene-batch-verify.log`，XML 位于独立输出的 `app/test-results/testDebugUnitTest/`。
- 迁入 CanvasSceneSnapshotTest 的 3 项 EGL pbuffer 增量（该类现共 6 项），保留前批 straight=true 夹具与 Runner 注册，覆盖失败批次像素保留、parts 容器冻结及 Reset 容量／像素；仅源码编译通过，未运行。
- 两位独立审查者复核最终三文件生产补丁、18 项新增 JVM 和 EGL 增量，均无阻断项。Queue 边界／预留分段、生产线程入口、SVG 请求冻结及提交后调度经源码核对；`mainHandler.post` 保留，不退回历史补丁中的 `view.post`。
- 主代理复核及基线差异确认：InfiniteEditor 的 surfacesForAttach／回调重绑、MainActivity、Editor、InfiniteCanvasView、GlWetPad、GlRenderer、TextureCache、GeometryStore、GlowTarget 均未改。第三批光晕失效与第四批快照路径保留。
- 审查限界：批次失败只拒绝该批 scene 更新，调用方此前的 document／history／paper 变更不回滚；CPU 消费部分失败、Scene／Frame 配对、worker 异常／跨绑定结果来源／文件寿命不属于本片。实际编辑器 SVG 调度拒绝及接收链未运行，通用队列回调测试不能替代它们；本批不能证明原黑块已解决。
- 本地保存点：生产补丁与 14 项队列 JVM 为 `996cb46e`，4 项真实 CanvasScene CPU 回归为 `f9eda215`；EGL 增量和记录另行保存后合并。

## 本地合并结果

- `codex/scene-batch-backport` 的 `996cb46e`、`f9eda215` 和 EGL／记录 `99988c8e` 通过 `cb8d1e61` 以 `--no-ff` 合入本地 master；合并前为 `175785ce`、工作树干净，合并无冲突。
- 合并后应用／测试源码及构建配置与已验证回迁分支完全一致，`git diff --check` 通过；主线 View／湿墨层保护、前四批补丁及既有审查／窗口待确认记录保留。没有对仅合并及文档差异重复运行测试。
- 保留原渲染分支及五条回迁分支，不反向合入原重构分支，不顺带开始下一批。RF01-F1～F4、D-1～D-7、其余 P／S／U 待修项不因本批合并而关闭。
- 本轮未生成 APK、运行 EGL／窗口仪器、启动模拟器、安装、推送或发布。真实编辑器 SVG 异步、窗口及目标 GPU 验收另行安排，原黑块效果未获真机验证。
