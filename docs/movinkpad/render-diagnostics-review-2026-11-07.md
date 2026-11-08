# 渲染诊断回迁合并后审查（2026-11-07）

用户要求：审查主分支最新合并 `276349a1`（第一批渲染诊断与 Surface 捕获回迁，提交 `b500aa1a`、`217531ee`、`db16f8df`）；审查结果先记录，待后续渲染回迁／合并完成后一起修复。
状态：已审查、已记录，未修复。补做验证：本地 master `cf2a4d9d` 上 `:app:compileDebugKotlin`、`:app:compileDebugAndroidTestKotlin` 通过，`RenderDiagnosticsTest` 11 项与 `SurfaceCaptureTest` 10 项 JVM 通过（JDK 21，构建输出在 `/private/tmp/xnotes-master-review/`）；仪器测试与目标设备未运行。
本轮边界：只新增本记录及入口链接；不改源码，不打包、安装、推送或发布。

## 结论

未发现影响正常使用的问题。生产接线只做观察：Surface 回调照常调用 `super`；`captureSurface` 只有仪器测试调用，应用界面无入口，未使用时捕获服务不会创建。下表问题只涉及诊断工具本身的准确性、开销或可用性。

审查由 Claude Code 的 code-review 流程完成，各项已对照源码核实；行号为 `276349a1` 时的位置。

## 待修清单（后续合并完成后一起处理）

| # | 位置 | 问题 | 建议 |
| --- | --- | --- | --- |
| D-1 | `gl/GlRenderer.kt:225`，`gl/RenderDiagnostics.kt` | 正式版始终开启诊断，但无读取方：每帧一次 `@Synchronized` 加锁、`nanoTime()`，并新建 `DrawnFrame` 与 `Size` | 仅 debug 构建或显式采集时启用；或改用 volatile／原子字段、避免逐帧分配 |
| D-2 | `gl/SurfaceCapture.kt:148` | `complete()` 以完整 `State` 相等判断前后状态，`State` 含 `latestFrame`（帧号、时间）；复制期间只要画出新帧（拖动、缩放、书写、连续渲染）即返回 `CHANGED` 并回收位图 | 比较时排除帧号／时间，只比较 Surface、context、尺寸等身份；或另行报告「期间有新帧」而不作废结果 |
| D-3 | `gl/SurfaceCapture.kt:121` | `request()` 只捕获 `IllegalArgumentException`／`IllegalStateException`；其他异常使 `pending` 与进程级预算（2 个请求）一直占用，超时也不释放 | 捕获范围扩大到 `Exception` 并走 `submissionFailed`；评估「回调永不到达」时的预算回收策略（目前为防 PixelCopy 仍在写入而故意保留） |
| D-4 | `gl/InfiniteCanvasView.kt:75` | 捕获只在 `replacementForAttach` 退休；画布直接关闭（不替换）时不关闭，挂起请求会留住 View、位图与预算名额 | 在画布最终释放路径关闭捕获服务 |
| D-5 | `gl/RenderDiagnostics.kt:155` | `rendererResized` 仅在 renderer 尺寸等于 `holder.surfaceFrame` 时发 TargetToken；错配或迟到的调整后直到下次 resize 都不记录帧 | 记录错配但仍对当前 Surface 发 token，或在后续一致的 resize／帧时补发 |
| D-6 | `gl/RenderDiagnostics.kt:134` | `contextCreated()` 在未挂接时返回 null（注释称会保留 context 身份）；卸载期间创建且被沿用的 EGL context 之后的所有记录被丢弃 | 按注释保留身份，挂接后补发 token |
| D-7 | `gl/RenderDiagnostics.kt:92` | 一次 detach／reattach 中 `surfaceRevision` 在 `detached()` 与 Surface 回调里各自递增，导出数据难以与框架事件一一对应 | 只在 Surface 生命周期回调中递增 |

## 按设计不修

- `InfiniteCanvasView.retireCapture` 只捕获 `Exception`、不吞 `Error`：回迁记录明确「不吞不可恢复 Error」，属有意设计。
- 合并时未编译／测试：系用户当次指示「暂不打包测试」，已在授权记录中说明；本次补做的编译与 JVM 结果见上。

## 背景与优先级

这批诊断原为排查「无限画布退出分屏黑区」准备。画布已取消分屏（`canvas-sketch-only-2026-11-07.md`），用途变小。修复时若决定只在 debug 启用（D-1），D-2 至 D-7 可按诊断工具实际使用需求取舍。

## 修复进展

D-1、D-3 已在 `b55822e4` 修复（分支 `worktree-fix-render-followups`），详见 [render-backport-review-followups-2026-11-07.md](render-backport-review-followups-2026-11-07.md#修复进展2026-11-07claude-code分支-worktree-fix-render-followups)。D-2 经用户决定保持现状，D-4～D-7 未修。
