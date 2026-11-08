# 第六批渲染回迁：图片解码结果与来源管理（2026-11-07）

用户要求：继续第六批回迁，保留主线 View／湿墨层替换保护，仔细审查并通过编译和 JVM 测试后合并；窗口测试另行安排。
状态：独立静态审查完成，生产与仪器源码编译通过，全量 2836 项 JVM 测试通过；已通过 `705bfa49` 合入本地 master。EGL／窗口及目标 GPU 验收未执行。
本轮边界：只回迁图片解码请求、来源成员及结果所有权闭环，执行源码编译、JVM 测试和本地合并；不运行 EGL／窗口仪器，不打包、安装、推送或发布。

## 来源与范围

基于已含前五批的本地 master `2a525106` 建立 `codex/image-decode-backport`，复用独立工作树，保留原分支。选择历史 `e697a84a` 的 decoder 子片：DecodeMailbox、TextureCache、CanvasScene、ImageDecoder，以及 InfiniteEditor 必要的私有解码绑定身份和完成／拒绝后请求重绘接线。不迁该历史提交中的 SVG job、裁剪、保存、Session／SceneStamp 或帧完成协议。

- 每次解码绑定具体来源和请求。空结果、异常和提交拒绝都能结清，重试次数有界；过期／低清结果不能覆盖新请求。未领取的独占 Bitmap 由邮箱释放，领取后由消费者释放，GL 上传成功前保留已有纹理。
- CanvasScene 在完整 Update 应用后同步图片来源成员；同源多对象、同文档重建保留合法 CPU 工作，最后成员移除或换文档撤销来源。InfiniteEditor 用私有身份区分文档安装，初始化与重建显式传入，同文档 View／湿墨层替换不更改它。
- context 通知只撤销 GPU 名称，保留有效 CPU 工作；close 终结 CPU 交付，不能从普通 detach 调用。实际最终 dispose／worker shutdown 仍待后续，不借本批扩大宿主生命周期变更。
- 主线 View／湿墨层替换、前五批保护、P／S／U 与 D 待修记录保留；不处理原重构分支 RF01-F1～F4，不把已应用更新的来源检查等同于 Scene／Frame 配对、模型回滚或 CPU 半应用失败恢复。

## 验证安排与边界

先用真实 TextureCache 调度／失败接收的 JVM 回归在旧生产上确认问题，再迁历史 12 项邮箱 JVM、7 项 EGL 源码并补实际场景成员关系回归。EGL 仅编译，不以 CPU 状态代替像素、窗口、目标 GPU 或原黑块效果验证。

使用 JDK 21、独立输出 `/private/tmp/xnotes-image-decode` 和项目缓存 `/private/tmp/xnotes-image-decode-cache`。实际结果与独立审查在下文记录。主线 master test2 的窗口 UID 及分屏夹具修复后待重跑事项仍保留。

## 实际验证与独立审查

- 使用 JDK 21.0.10、Gradle 8.13、AGP 8.12.3、SDK 36；构建配置、包名、versionCode 和签名配置未改。
- 先在旧生产上运行 2 项真实 TextureCache 调度回归，两项均按预期行为断言失败：实际无效图片解码返回 null 后 pending 为 1 而非 0；RejectedExecutionException 直接逃逸。夹具关闭 Robolectric 的无效图片伪成功，并先断言 ImageDecoder 确实返回 null，不是编译失败或 GPU 替身结果。日志 `tmp/image-decode-red.log`，失败 XML `tmp/image-decode-red-results.xml`。
- 修正后两项通过，验证失败接收、同目标在途去重、默认两次失败后停止重试，以及 context 通知恢复失败预算；不创建成功位图或 GL 纹理。
- 迁入历史 12 项 DecodeMailbox JVM，覆盖领取／关闭所有权、同来源重建、换绑定／退出再入、低高清请求顺序、旧请求不得清新请求、有限重试、调度拒绝、身份而非 equals 比较、并发关闭及锁外清理。
- 新增 5 项真实 CanvasScene CPU 来源回归，覆盖共享来源末成员删除、几何更新、同绑定完整重建、新绑定复用同 ImageData、失败批次与空 reset、换源及空几何替换。走真实 producer→drainEdits→TextureCache／mailbox，只替换 decoder 为 AssertionError guard；有效任务只排队，执行的过期任务必须在解码前被拒绝，不伪造成功上传或像素。共新增 **19 项 JVM**。
- `:app:compileDebugKotlin :app:compileDebugAndroidTestKotlin` 通过，日志 `tmp/image-decode-compile.log`；随后 `:app:testDebugUnitTest` 全量 **256 套件、2836 项**通过，失败／错误／跳过均为 0，前五批回归包含在内。日志 `tmp/image-decode-unit.log`，XML 位于独立输出的 `app/test-results/testDebugUnitTest/`。
- 迁入 CanvasSceneSnapshotTest 的 7 项 EGL 增量（该类现共 13 项），保留原 6 项及 straight=true。新用例覆盖来源成员、换绑定 ready 拒收、失败／拒绝预算、低清迟到、CPU 完成前后 context 通知、上传失败保留旧纹理及 close 后拒收；仅编译，未运行。低清 worker 超时显式写入失败槽，避免被普通解码异常处理吞掉而假通过。编译后仅更正清理注释：ready 位图由 release→close 回收，不由 context 通知回收。
- 两路独立审查复核五文件生产片、邮箱及 EGL 测试，无阻断；额外 5 项 CPU 来源回归由另一审查者复核。审查发现并补齐 InfiniteEditor 的失败／拒绝后 requestRender 唤醒，保证静止画面仍有机会消费终结结果。
- InfiniteEditor 仅增加私有 imageDecodeBinding 的初始化／安装／reset 接线及 decodeOn 唤醒；surfacesForAttach、主线回调重绑、MainActivity、Editor、InfiniteCanvasView、GlRenderer、GlWetPad、FrontInk 及 GeometryStore／GlowTarget 均未改。前五批完整批次、快照、光晕及替换保护保留。
- 保存点：邮箱与 12 项 JVM 为 `74df5c86`，真实接线及 2 项调度 JVM 为 `6b13d461`，5 项场景 CPU 回归为 `9db07cb1`；EGL 源码与本文单独保存后合并。

## 保留的验证与协议边界

CPU 回归证明请求身份、来源成员及失败结清，不证明实际 GL 上传、窗口／宿主替换或目标 GPU 表现。EGL 的 context 通知用例没有销毁持有旧纹理的真实 context；旧低清返回时高清仍 pending 的隔离由邮箱 JVM 验证，不把未运行的像素用例当作额外运行证据。

InfiniteEditor 的实际失败唤醒、文档安装／重挂接线本轮只静态审查；最终 dispose／worker shutdown 尚无生产接入，全局解码内存预算与文件租约不属于本片。SVG job、裁剪来源、Scene／Frame 配对、CPU 半应用失败恢复及模型回滚仍保留后续范围。原重构分支 RF01-F1～F4、主线 D／P／其余 S／U 待修项未因本批关闭，不宣称原黑块已解决。

## 本地合并结果

- `codex/image-decode-backport` 的 `74df5c86`、`6b13d461`、`9db07cb1` 和 EGL／记录 `a643947c` 通过 `705bfa49` 以 `--no-ff` 合入本地 master；合并前为 `2a525106`、工作树干净，无冲突。
- 合并后应用／测试源码及构建配置与已验证回迁分支完全一致，`git diff --check` 通过。主线 View／湿墨层替换、前五批保护及既有审查／窗口待重跑记录保留，没有因仅合并及文档差异重复运行测试。
- 保留原渲染分支及六条回迁分支，不反向合入原重构分支，不顺带开始下一批。本轮未生成 APK、运行 EGL／窗口仪器、启动模拟器、安装、推送或发布；GPU、窗口与原黑块效果验收另行安排。
