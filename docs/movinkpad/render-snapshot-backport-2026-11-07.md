# 第四批渲染回迁：图片绘制与录音关联快照（2026-11-07）

用户要求：继续下一批回迁，保留主线 View／湿墨层替换保护，独立审查并通过编译和 JVM 测试后合并；窗口测试另行安排。
状态：独立静态审查完成；生产与仪器源码编译通过，全量 2799 项 JVM 测试通过；已通过 `2f960218` 合入本地 master。EGL／窗口及目标 GPU 验收未执行。
本轮边界：仅回迁图片绘制参数与录音关联信息的入队快照，以及录音打点／解除关联的通知闭环；执行源码编译、JVM 测试及本地合并，不运行 EGL／窗口仪器，不打包、安装、推送或发布。

## 来源与范围

基于已含前三批回迁的本地 master `af42ec49` 创建 `codex/render-snapshot-backport`，复用独立工作树，保留原分支。选取历史 `d292cc2b` 的六个生产文件小补丁；InfiniteEditor 按主线现有 init／回调接线适配，不复制旧分支的 CanvasRenderHost 或 RenderSession。

- 图片入队时冻结 source、rect、orientation、angle、flip、part，GL 绘制只读快照，对象引用仅作身份；再次 upsert 才发布新值。
- 普通 upsert 与图片 upsert 同时冻结不可变 SyncMark。后续录音打点使用元数据消息更新已有 Record；解除关联通过命令包装，在初次执行、撤销、重做后均通知。通知按成员身份过滤／去重，并刷新录音索引，不重建几何，也不复活隐藏、移除或尚未提交的 Record。
- ImageData 继续按原有只读原件约定共享；本批不增加文件租约、不处理解码／SVG／裁剪的迟到结果，不迁完整队列事务、场景版本、帧完成票据、异常传播或保存／录音耐久协议。
- 主线 View／湿墨层替换和画布独立模式必须保留；第三批光晕失效继续保留。RF01-F1～F4 与诊断 D-1～D-7 不在本批。

## 验证安排与已有边界

先以真实 CanvasScene 入队／消费的 JVM 反例确认旧图片引用会读到后改值，再迁历史 9 项 JVM 与 3 项 EGL 测试源码，补所需接收端回归。EGL 源码只编译，GL 替身／CPU 断言不作为像素或目标 GPU 证据。

独立输出 `/private/tmp/xnotes-render-snapshot`，项目缓存 `/private/tmp/xnotes-render-snapshot-cache`，JDK 21。实际结果与审查结论在下文补齐。

保留主线 master test2 的历史失败记录：窗口输入 UID 不匹配仍未确认原因；保存分屏夹具已改为不同文档（`dcfc2eb9`），但仪器重跑尚未完成。本批不将它们标为已通过，也不借用历史窗口结果。

## 实际验证与独立审查

- 使用 JDK 21.0.10、Gradle 8.13、AGP 8.12.3、SDK 36；构建配置、包名及 versionCode 与基线一致。
- 新写 3 项真实 CanvasScene 图片入队／消费反例，在未修改生产代码时均因 rect／source 内容已被后续修改而失败，不是接口缺失或编译失败。日志 `tmp/render-snapshot-red.log`，失败 XML `tmp/render-snapshot-red-results.xml`。
- 修正后扩展为 6 项真实队列回归，覆盖六字段快照、再次 upsert、item 身份及 z 保留、两种 upsert 的 null／非 null SyncMark、元数据列表与标记冻结、几何和 Record 保留、不复活缺失对象及 sync→upsert 顺序。仅反射调用实际消费入口并读取结果，不创建 GL context 或模拟像素。
- 迁移 9 项历史 JVM：ImageDrawSnapshot 2 项、CanvasSync 6 项、InkFade 增加 1 项，验证值冻结、通知成员／身份过滤、列表所有权、无需读取几何及解除关联撤销重做。共新增 15 项。
- `:app:compileDebugKotlin :app:compileDebugAndroidTestKotlin` 通过，日志 `tmp/render-snapshot-compile.log`；`:app:testDebugUnitTest` 全量 251 套件、2799 项通过，失败／错误／跳过均为 0，日志 `tmp/render-snapshot-unit.log`。前三批 JVM 回归包含在本次实际执行中。
- 迁入历史 `CanvasSceneSnapshotTest` 3 项 EGL pbuffer 测试并保留 Runner 其他注册；仅将两采样笔画夹具设为 straight=true，避免平滑缩短线段使固定像素探针落在端点。编译通过，未运行。
- GL／通知调用链及仪器夹具由独立审查者复核，新 6 项队列回归由另一代理编写后交独立审查；最终无必修项。图片变换／裁剪／撤销均沿已有模型通知发布，导入／复制／录音 ID 重映射在 add 前设置关联；录音打点后再通知，解除关联的执行／撤销／重做均在变更后通知。
- `InfiniteEditor` 仅局部改动 modelListener、history.onPush 和 unlinkSelectedAudio；surfacesForAttach、主线回调重绑、MainActivity、Editor、InfiniteCanvasView、GlWetPad、GlRenderer、TextureCache、GeometryStore 和 GlowTarget 均未改变。CanvasScene 的第三批光晕失效两入口保留。
- 源码／JVM 验证不代替 InfiniteEditor 真实录音打点与前缓冲运行接线、GPU 像素、窗口、目标设备或原黑块效果验收。文件寿命、异步任务来源、帧／播放进度版本配对仍属后续范围。
- 保存点：生产闭环与历史 9 项 JVM 为 `e53e458e`，6 项真实队列回归为 `5cf679ef`；EGL 源码及本文随后单独保存。

## 本地合并结果

- `codex/render-snapshot-backport` 的 `e53e458e`、`5cf679ef` 和 EGL／记录 `6d93cf42` 通过 `2f960218` 以 `--no-ff` 合入本地 master；合并前为 `af42ec49`、工作树干净，合并无冲突。
- 合并后应用／测试源码及构建配置与已验证回迁分支完全一致，`git diff --check` 通过；主线 native View／pad 替换、第三批光晕修正、master test2 的历史失败及分屏夹具修复记录均保留。没有对无源码变化的合并重复运行测试。
- 保留原渲染分支及四条回迁分支，不反向合入原重构分支，不顺带处理 RF01／诊断待修项或下一批。
- 本轮未生成 APK、运行 EGL／窗口仪器、启动模拟器、安装、推送或发布。窗口与目标 GPU 验收另行安排，原黑块效果未获真机验证。

## 合并后审查

合并后审查待修项 S-1～S-6（未修复）见 [render-backport-review-followups-2026-11-07.md](render-backport-review-followups-2026-11-07.md)。
