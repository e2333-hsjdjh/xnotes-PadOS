# 第一批渲染回迁：诊断与 Surface 捕获（2026-11-07）

用户要求：先整理诊断和 Surface 捕获的回迁补丁，保留主线现有 View／湿墨层替换保护，仔细审查后合入主分支，暂不打包测试。
状态：补丁已实现并完成独立静态审查，未见剩余必修项；已通过 `276349a1` 合入本地 master。未编译、运行测试或完成目标设备验证。
本轮边界：仅回迁诊断观察、显式 Surface 捕获及对应回归源码，已按授权在审查后本地合入 master；不打包、安装、推送或发布，不合入新渲染会话／帧协议或稳定布局。

## 来源与基线

复用独立工作树 `/Users/kita/.codex/worktrees/render-lifecycle/xnotes`，从本地 `master` 的 `986781e5` 建立 `codex/render-diagnostics-backport`。原 `codex/render-lifecycle` 源码保存点 `0f963ab6` 保留，不从该分支整文件覆盖主线。

- 诊断：取 `ae3cdd99` 的观察接线，连同 `34a6c262`、`b96616bb` 的 context／Surface 失效修正；已保存为 `b500aa1a`。
- 捕获：取 `5d03b4b1` 的 SurfaceCapture、回归与 debug 窗口夹具，适配主线的原生 View 退休；已保存为 `217531ee`。
- 本轮读取远端后 `origin/master` 仍为 `b5175e8e`。它比本地主线多 Release 98 的版本／交付记录，本地主线则已有画布取消分屏。本批以本地 master 为目标，不顺带带入版本变更或发布记录。

合并前主工作树干净且仍为 `986781e5`；`276349a1` 以 `--no-ff` 合入回迁分支 `db16f8df`，没有冲突，合并后的 app 源码与已审回迁分支完全相同。原渲染分支与回迁分支均保留；工作结束回到原渲染分支。

旧提交携带的 README、任务断点和重构计划没有整批迁入。历史 `ed996c8c` 的 2727 项 JVM／110 项 Android 12 模拟器结果不覆盖此次适配。

## 实际改动

诊断记录 native View、attachment、观察到的 context／Surface 代次、布局／holder／renderer 尺寸、首个绘制回调及有限错误分类。事件环有界，普通帧只更新最近帧，不逐帧刷日志；不接收笔记内容、文件名或任意错误文本。诊断对象不授予绘制许可，也不改变原有完成回调或渲染调度。

每个新 native View 创建独立 RenderDiagnostics；GlRenderer 的 `initialContextGen` 首参数及主线替换时继承旧 renderer.contextGen 的行为保留。诊断 contextId 是本 View 的观察计数，不与跨 View 延续的 renderer.contextGen 混为一谈。

SurfaceCapture 仅在显式调用 `captureSurface` 时按需创建，使用 PixelCopy 读取真实 Surface，不通过模型重绘替代，也不自动写磁盘或上传。单次尺寸上限、跨 View 的像素／请求数预算、超时、源变化、提交失败及 bitmap 所有权沿用已审查实现。调用者仅在 SUCCESS 时拥有位图并负责 recycle。

旧 View 真正被 `replacementForAttach` 退休时关闭自己的捕获服务；普通 detach／SurfaceDestroyed 不永久关闭。未使用过的服务不因退休而创建，退休后首次请求仍返回 CLOSED。关闭先撤销调用者回调，迟到 native completion 仍负责最终回收位图／配额；超时或关闭均不提前回收 PixelCopy 可能还在写入的位图。退休通知的普通异常只在诊断边界隔离，不能阻断宿主替换，也不吞不可恢复 Error。

## 主线保护与未带入范围

`MainActivity`、`Editor`、`InfiniteEditor`、`GlWetPad` 及其主线替换／输入恢复逻辑不变；保留两类 `surfacesForAttach` 调用、首次未挂接守卫、同步退出旧 GLThread、旧完成回调隔离、held/live 墨迹回补、前缓冲偏好，以及画布禁止分屏。已有 InfiniteSurfaceAttach 的 sharedTest／JVM／仪器三份源码及仪器 Runner 注册保留。

本批不迁入 CanvasRenderHost 固定宿主、新 RenderSession、SceneStamp、DrawReceipt、GeometryStore 新异常、保存／录音协议、光晕修正或稳定工具栏布局。原渲染分支 RF01 的四项问题仍留在原分支处理；本次合入不宣称解决原设备黑屏。

## 审查与验证边界

生产接线由主审实现，诊断／捕获资源边界与主线替换兼容由独立审查者复核；回归源码另行迁移与交叉审读。两片最终静态复核均未见剩余必修项。适配时补清并实现了两条约束：退休不得为关闭而初始化捕获服务，关闭通知的普通异常不得阻断必要的宿主替换。

共迁移／补充 21 项 JVM（诊断 11、捕获 10）与 4 项窗口仪器测试源码。窗口源码在原真实 Surface／系统截图对照和未挂接拒绝用例之外，增加新旧 View 诊断与捕获身份、contextGen 延续、detach 的 UNAVAILABLE 与退休的 CLOSED、新 View 像素；另一用例分别覆盖未初始化捕获的退休，以及 pending 捕获的关闭通知抛异常而宿主替换继续、真实 native callback 最终回收位图。未重置进程预算或伪造 native completion。

仅执行静态差异／空白检查及源码核对：上述主线保护文件与基线无差异，原重挂三份测试源码与注册保留；SurfaceCapture 核心与 `5d03b4b1` 逐字一致。debug 窗口只在 debug 清单注册、未导出；生产捕获不自动导出截图。仅仪器夹具显式输出一组有界本地对照图片与元数据。

诊断 TargetToken 仅表示应用观察到的状态，不能识别所有同尺寸原生 buffer queue 变化；latestFrame 是 draw 回调完成信息，不是 swap／显示证明。捕获 SUCCESS 表示复制成功且前后观察状态相同，仍不能证明与系统截图原子同帧。稳定的尺寸错配或缺失帧信息会作为诊断证据保留。

本轮按用户要求不编译、不运行 JVM／Android 测试、不打包或安装。源码静态审查与差异检查不替代运行结果；后续软件回归及 MovinkPad／天玑 Android 16 验收单独安排。

## 合并后审查

合并后审查与待修清单（未修复，待后续合并完成后一起处理）见 [render-diagnostics-review-2026-11-07.md](render-diagnostics-review-2026-11-07.md)。
