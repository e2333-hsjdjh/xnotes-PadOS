# 无限画布退出分屏黑屏：深入核查（2026-11-06）

用户要求：继续仔细排查应用内退出分屏后无限画布局部黑屏；天玑 8350、Android 16，禁用前缓冲以及平移、缩放均不能恢复，旋转未测试，用户称使用当时最新版本。
状态：已完成源码交叉审查与取证脚本验证；目标设备根因未确认，尚无经过真机验证的修复。
本轮边界：未打包、安装、推送或发布；产品仅纠正一处误导性注释，不修改渲染行为。新增只读 ADB 取证脚本。

## 基线与证据分级

本轮起点为干净的 `f012942e`（本地配置 0.9.8 / versionCode 95）。用户此前的“最新版本”反馈发生在上一轮，不能据此认定设备已升级到 95。此前事实与 Android 12 / Release 89 对照见 [11 月 5 日记录](infinite-split-black-2026-11-05.md)。本轮分别审查 Compose 宿主、GL 渲染和平台 Surface 三条路径。

### 能由源码确认的事实

1. `SplitHost` 在单窗格时把两个 extent 都设为 full、位置回到 0；主/副窗格和交换顺序都有全屏分支。没有发现“逻辑上仍限定半屏”的确定缺陷。
2. `PaneToolbarAround` 的单文档/分屏 Compose 分支会重新创建内部 AndroidView 宿主。`detached(canvas.surfaces)` 复用的是 Java View 容器，并不保证 EGL 上下文延续。已修正 `MainActivity` 中声称分屏开关会保持 GL context 的注释。
3. 本机 SDK 36.1 revision 1 的 `GLSurfaceView.onDetachedFromWindow` 等待 GLThread 退出，重挂启动新线程。应用 `GlRenderer.onSurfaceCreated` 重建渲染资源，`onSurfaceChanged` 设置 GL viewport。旧模拟器日志也记录了 context 代次递增。因此“旧的半屏 EGL 上下文始终没重建”不是现有证据支持的解释。
4. `InfiniteCanvasView.onSizeChanged` 更新 viewport 并 publish，Compose update 也 publish；平移/缩放发布新帧。没有找到普通状态下完全漏发重绘的路径。
5. `CanvasScene` 正常路径中的 scissor、color mask、stencil 状态均有对应恢复。GL viewport 只限定光栅化映射，并不限制 glClear；因此单独遗留半屏 viewport 不能解释白纸整块持续黑色。尚不能从这些检查排除所有 GPU/驱动错误。
6. 无限画布应用内截图入口使用 `renderRegionBitmap` 从模型离屏重画，并非实际屏幕 Surface 的读回。应用内截图正常不能用来证明显示缓冲正常。

### 平台机制支持、但未在故障设备证实的假设

本机 SDK 36.1 的 `SurfaceView.java` 有三条相关通路：

- `mRtDrivenClipping = Flags.clipSurfaceviews()`：启用与否由平台 flag 决定，不是只看 Android 主版本。
- RenderNode 位置监听器将祖先裁剪换算到缓冲坐标，独立提交 SurfaceControl crop。
- View 绘制另行在应用窗口上产生透明区域；位于窗口下方的不透明 SurfaceView 还可有默认黑色背景层。

因此，**GL 缓冲、合成器 crop、窗口透明区域是不同层面的状态**。若后二者不同步，可能露出黑底，且持续提交 GL 帧不一定修正它。这比“少刷新一次”更符合当前对照，但尚未读到故障设备的错误 crop 或实际缓冲，不能将其称为已确定根因。SDK 36.1 不等于该厂商 Android 16 的确切源码，也没有官方证据证明本故障是已知天玑驱动缺陷。

参考：[AOSP SurfaceView 源码](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/view/SurfaceView.java)、[AOSP GLSurfaceView 源码](https://android.googlesource.com/platform/frameworks/base/+/master/opengl/java/android/opengl/GLSurfaceView.java)。精确核对对象是本机 `sources/android-36.1/`，在线 master 可能已变化。

## 实际 EGL 像素对照

在 Android 12 ARM64 / ANGLE / SwiftShader 上，通过临时 Java dex 的 `app_process` 加载本机已有未混淆 Debug APK 中的 GlRenderer；没有安装 APK、启动应用或读取笔记。实际创建 64×64 EGL pbuffer，绘制后销毁并改为 128×64，再重复重绘；另销毁并重建 EGL context。白纸黑网格与黑纸白网格分别对照同主题全屏基准。

最终 **18 组对照全部通过**：逐通道像素差异均为 0，左右各检测到 252 个网格像素，GL 错误均为 0。最初探针误把默认网格纸当纯色，导致 504 个网格像素触发断言；修正为同主题全屏基准后再运行，不能把最初探针错误当产品缺陷。最终版本确实替换不同尺寸 pbuffer，不只是修改 GL viewport。

严格边界：这是纸面/网格的离屏渲染试验，renderer 未接入 CanvasScene，没有覆盖笔迹、图片、图层、MSAA、真实窗口 Surface、Compose、系统合成或天玑 GPU。加载的是 2026-11-03 旧 Debug 构建；Git 中 GlRenderer 自 `4959f9fd` 后未改变，但不能将旧 APK 测试算成当前整包回归。

- 旧 APK：`/private/tmp/xnotes-ai-settings-build/app/outputs/apk/debug/app-debug.apk`。
- SHA-256：`19626058e5b3116491dd93e910239cdd5fd86fa7778d3373490254e0ed23b3a7`。
- 临时探针及最终结果：`tmp/infinite-split-black/probe/RendererProbe.java`、`real-buffer-result.txt`。初版仅 viewport 对照日志为 `result.txt`，不作为真实缓冲替换证据。
- 未运行当前整包 JVM/Android 仪器测试；本轮没有渲染行为改动。

另发现一个独立静态风险：`CanvasScene.onContextCreated()` 重建光晕纹理但未清空 `haloKey` / `liftHaloKey`，同尺寸同视点重挂时可能误用缓存。它依赖特定光晕内容，且平移/缩放会改变缓存键，尚不能解释本报告的持续黑区。本轮不将其当作根因或混入修复。

## 只读取证脚本

新增 [`scripts/capture-canvas-surface.sh`](../../scripts/capture-canvas-surface.sh)。需要已授权 ADB 的故障设备，不要求安装新 APK。用新的目录分别保留正常单文档、分屏、黑屏三阶段：

```sh
bash scripts/capture-canvas-surface.sh DEVICE_SERIAL com.xnotes.movinkpad tmp/canvas-before
# 在设备上进入分屏后：
bash scripts/capture-canvas-surface.sh DEVICE_SERIAL com.xnotes.movinkpad tmp/canvas-split
# 退出分屏出现黑块后，保持故障画面：
bash scripts/capture-canvas-surface.sh DEVICE_SERIAL com.xnotes.movinkpad tmp/canvas-after
```

若 adb 不在 PATH，用 `ADB=/实际路径/adb` 指定。分支测试包必须传其实际包名。脚本不启动/停止应用，不清日志，不改变尺寸或旋转，不自动上传；输出含截图内容及设备/应用信息，分享前需检查。

采集系统截图、限定标签的 GL/Surface 日志、SurfaceFlinger 层级和详细文本、gfxinfo、包版本与设备构建字段。明确指定设备与包名，包不存在时拒绝；不覆盖旧目录，文件默认仅当前用户可访问；单项失败保留 stdout/stderr，并继续后续采集。

`status.tsv` 仅代表命令退出码；部分厂商可能以退出码 0 返回权限拒绝文本，仍须检查原文。普通 SurfaceFlinger 转储也可能缺少有效 crop，不能将缺字段解释为正常。

### 脚本验证

- `bash -n`、`git diff --check` 通过。
- 无设备、包不存在、非法包名均明确失败，未创建对应结果目录；重复输出目录被拒绝，原样本保留。
- 在现有 Android 12 ARM64 模拟器中以已有系统设置包验证成功采集路径，13 项命令成功，截图可读取。此项只证明脚本能工作，不是 XNotes 回归。
- 本轮启动已有 AVD 时恢复的是不含 XNotes 的旧快照，没有重新安装应用。首版脚本曾将“不存在包的 dumpsys 返回 0”计为成功，检查原始输出后补上 `pm path` 预检，并验证缺包被拒绝；不把早期结果计作应用采集成功。

## 真正区分根因所需的证据

最有判别力的是同一故障时刻的 **PixelCopy(GLSurfaceView)** 与 **系统截图**，同时记录 View、holder.surfaceFrame、EGL surface、GL viewport 的尺寸和代次：

| 观察 | 优先调查 |
| --- | --- |
| 真实 Surface 读回完整，系统截图存在黑块 | 窗口透明区域、Surface 裁剪与合成 |
| Surface 读回也有黑块或错误尺寸 | EGL 缓冲、GL 状态和渲染线程 |
| 系统截图完整，设备肉眼仍见黑块 | 显示输出/硬件合成的后续路径 |

本次脚本只能取得系统截图和外部日志，**不提供 PixelCopy**；现有应用也没有这项诊断入口。若外部信息不足，需要另行准备带真实 Surface 读回的诊断构建，再按仓库规则取得本次打包/安装授权。不能以模型重画截图替代它。

尚未实施整 View 重建、改为 TextureView、手动覆盖系统 crop 或按芯片禁用功能等候选方案。它们都会改变显示生命周期，未取得区分性证据前不能宣称是根因修复。
