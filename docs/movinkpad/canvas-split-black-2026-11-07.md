# 正式版无限画布退出左右分屏右侧黑屏（2026-11-07）

用户要求：用户在自己的 MovinkPad Pro 14 正式发布版复现：无限画布作为主窗口，进入分屏后关闭另一侧，黑块固定出现在右侧，与画布原先在左还是右无关；画布原在下方时关闭另一侧不出现黑屏。要求从主分支另开分支继续，先不处理渲染重构。
状态：已实现局部候选修复；源码与 Android 测试编译通过，687 项相关 JVM/Robolectric 测试通过；黑屏效果待真机验证，底层裁剪根因尚未确认。
本轮边界：从本地 master 建立独立修复分支，源码修改、编译、JVM 测试、本地提交及用户随后授权的本地主分支合并；不打包 APK、安装、推送或发布。

## 独立工作基线

- 分支 `codex/canvas-split-black`，工作树 `/Users/kita/.codex/worktrees/canvas-split-black/xnotes`。
- 起点为本地 master `0b35d341`，构建配置 0.9.8 / versionCode 96。用户只确认正式发布版，没有直接读取其设备安装信息，不将基线配置冒充实际安装版本。
- 原 `codex/render-lifecycle` 工作树与分支保持原样，其中已有未提交的 RenderDiagnostics 改动未携带、暂存或覆盖。
- 本分支不包含渲染重构的 P0/P1；不将正式版反馈记作重构候选的测试结果，也不沿用该分支旧的打包测试授权。
- 本轮 ADB 清单为空，没有直接采集用户设备。

## 复现矩阵与边界

| 保留的无限画布在分屏时的位置 | 关闭另一侧后的观察 |
| --- | --- |
| 左侧 | 右侧区域全黑 |
| 右侧 | 仍为右侧区域全黑 |
| 下方 | 未出现黑屏 |
| 上方 | 尚未报告 |

“全黑”按用户紧接着的定位记录为右侧区域，不推定整个屏幕全黑。随后用户确认：改成明显不均分后，黑块从画布原来的宽度边界开始，并非固定从屏幕中央开始。未取得像素测量值，也未逐项确认该不均分对照是否同时覆盖左右两种位置。工具栏位置、另一侧文档类型/空首页、纸面主题、图层与内容条件未取得完整对照。

设备确认是 MovinkPad Pro 14；该机本轮 Android 构建号和 GL_RENDERER 未读取。此前天玑 8350 / Android 16 的反馈另行保留，不能再将故障限定为该芯片或 Android 16 独有。此前禁用前缓冲、平移缩放无效的反馈也不冒充这台设备本轮新测结果。

## 正式版源码核查

1. `MainActivity.SplitHost` 的横/纵分屏共用 extent 算法。退出分屏时两侧 extent 都是 full，位置回到 0；未发现 X 方向写死半宽的分支。
2. `InfiniteCanvasView` 未固定 holder 尺寸或手动设置 SurfaceControl crop；`onSizeChanged` 同时更新宽高并 publish，`GlRenderer.onSurfaceChanged` 同时设置 GL viewport 宽高。
3. 正式版 `PaneToolbarAround` 仍在单/双窗格间切换 Compose 分支，`AndroidView.factory` 通过 `detached(canvas.surfaces)` 移动同一个无限画布容器。这是已确认的生命周期触发点，不等于已证明的黑屏根因。
4. 湿墨层异步退休与“关闭窗格原位置留下一层”的解释匹配度较低：画布从右侧回到全屏仍然右黑，并非跟随被关闭的左侧位置。不能因此完全排除所有图层问题。
5. GL 光晕离屏绘制会通过 `GlowTarget.unbind(frame.widthPx, frame.heightPx)` 恢复 viewport，但没有水平专用算法。尺寸回调与帧快照交错可能短暂恢复旧尺寸，仍不足以解释持续黑区；正常后续帧会更新相关尺寸，glClear 本身不受 viewport 限制。

最符合目前空间规律的工作假设是：**剩余画布恢复全屏后，其局部 X 方向有效显示范围仍受旧宽度限制**。非均分对照进一步支持这一点，比“被关闭窗格在原屏幕位置留下遮挡”更符合证据。但若整个旧裁剪矩形完全不更新，也应预测纵向扩展出现底部异常；原下方正常对这一笼统解释构成约束。当前不能指定是 GL viewport、buffer、祖先裁剪还是窗口透明区域。

## 局部候选修复

仅在 `AndroidView.factory` 跨宿主重挂且旧 `InfiniteCanvasView` 已完成 detach 后，经 `InfiniteEditor.surfacesForAttach()` 替换底层 `InfiniteCanvasView` 和 `GlWetPad`。普通重组、首次挂载与同宿主尺寸变化不会创建新的 View。保留容器、工具覆盖层、文档、撤销、scene 和同一个 `CanvasViewport`，不重新打开文档或重置视点。

- 先移除旧宿主、等待旧 GL 线程结束，再创建新 View；`GLSurfaceView` 的 detach 等待线程退出行为可见 [AOSP 源码](https://android.googlesource.com/platform/frameworks/base/+/master/opengl/java/android/opengl/GLSurfaceView.java)。这只能支持线程交接约束，不能证明目标设备的裁剪故障。
- 新 renderer 延续 context generation，防止共享 scene 的图片异步任务把旧 context 误认成新 context。不继承旧 `afterFrame` 队列，因为其中的闭包会把任务投向旧 View 已退出的线程。
- 归还已提交但仍暂存在前缓冲的笔迹；正在书写的整笔恢复到普通 wet scene，不清除交互对象。旧 pad 的回调断开，迟到的帧完成通知不能覆盖新宿主的等待状态。
- 保留前缓冲开关、夜间笔迹设置、纸面与小地图、光标、触摸/悬停/按键和二/三/四指手势。
- 矢量网格、内存提示及录音恢复的主线程任务改由 editor 的 Handler 派发，避免任务留在已经废弃的 View 待挂载队列中。

该候选针对正式版已确认的重挂路径，不引入渲染重构分支。独立复审发现并补齐了 surfaceLost 恢复 live wet 后需主动 publish 的遗漏。另记录一个既有问题：`CanvasScene` 的光晕 cache key 未随 context 重建失效，可能影响同尺寸 EGL 重建后的光晕；原重挂路径也会重建 EGL，该问题不解释整片按旧宽度出现的黑区，本轮不混入修复。

## 验证与待验收

- JDK 21、隔离的 `tmp/validation/build` 与 `--project-cache-dir tmp/validation/project-cache`，只编译源码和测试，不生成 APK。
- `:app:compileDebugKotlin`、`:app:compileDebugAndroidTestKotlin` 通过。
- `:app:testDebugUnitTest --tests 'com.xnotes.pen.*' --tests 'com.xnotes.canvas.*' --tests 'com.xnotes.core.infinite.*'`：57 个测试类、687 项测试，0 失败、0 错误、0 跳过。日志与 XML 在忽略目录 `tmp/validation/`。
- 其中新增 4 项共享生命周期用例，同时提供 Robolectric 与 Android runner 入口：首次保留、320→960 扩宽后的状态/输入绑定、暂存笔迹与旧 renderer 队列隔离、书写中重挂后完成及撤销重做。Robolectric 4 项均通过。用例显式调用 detach 回调，未挂入真实 Window、未创建 EGL 上下文；书写用例关闭前缓冲，证明坐标与提交保留，不证明真实前缓冲笔段已显示，也未复现目标设备黑区。
- 首次 JVM 执行在 Robolectric 的 `SharedMemory.close` 因 JDK 模块访问限制失败，未到达业务断言；按 [Robolectric 官方 JDK 17+ 配置说明](https://robolectric.org/getting-started/#running-with-java-17-and-higher)为 Test JVM 增加必要的模块访问参数。随后发现 ShadowEGL14 缺失 EGL 空句柄初始化，仅在 host test fixture 补上 3 个零句柄常量，未修改产品实现或 Android 测试来适应模拟环境。
- Android 仪器测试、Compose 实际挂载、目标 GPU 与屏幕像素均未执行；历史模拟器及 EGL 探针不能替代此次修复验收。

真机验收应覆盖左右非均分退出、上下分屏、连续进出、前缓冲开/关、已有图片和不同笔种、退出后右侧新区域书写及撤销；记录设备、系统构建与安装版本。如果候选仍有黑区，再结合真实 Surface PixelCopy 和系统截图区分 buffer 内容与合成裁剪。已有 `scripts/capture-canvas-surface.sh` 可采集图层、尺寸及系统截图，但不具备 PixelCopy 能力。

用户随后授权本地合入 `master`，原修复分支保留。合并前主分支仍是 `0b35d341`，没有需要整合的新应用改动，因此沿用上述候选源码的验证结果；本次合并不表示真机黑屏已验收。授权范围见 [一次性记录](authorization-history.md#2026-11-07-无限画布退出分屏黑区候选合并)。
