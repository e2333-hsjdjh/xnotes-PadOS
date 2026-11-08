# 无限画布专供画画与草稿，取消分屏（2026-11-07）

用户要求：用户认为无限画布本意是画画与草稿（sketch）而非笔记，此前为让画布参与分屏所做的调整与原意相悖；思维导图在功能上应是画布的超集，偏笔记的需求交给未来的思维导图模块。决定取消画布分屏，画布专供画画与草稿。已合入的分屏黑区候选修复暂不撤销。
状态：已实现；编译与全量 JVM 测试通过（JDK 21，2755 项，0 失败）；Android 仪器测试源码编译通过，未运行；目标设备未验证。
本轮边界：独立工作树 `worktree-canvas-no-split` 源码修改与本地提交；不打包、安装、推送、合并或发布，不改动 `codex/render-lifecycle` 与 `codex/mind-map`。

## 行为

| 入口 | 处理 |
| --- | --- |
| 画布工具栏分屏按钮 | 打开画布时不显示；处理函数同样拒绝画布窗格 |
| 文件预览「在旁边打开」 | 当前文档或所选文件为画布时不可用 |
| 两文件分屏打开 | 任一为画布时提示 `split_canvas_unsupported`，不创建第二窗格 |
| 分屏窗格首页选择文件 | 另一窗格已有文档或首页时，拒绝打开画布并提示；单窗格首页仍可打开画布 |
| 含画布的已保存 `.xsplit`（含主菜单「恢复分屏」） | 在替换当前工作区前拒绝并提示 `split_canvas_saved_unsupported`，当前文档与未保存修改保持不变；文件本身及其引用的文档不改动，可删除 |
| 上次会话的分屏工作区含画布 | 只恢复主窗格自身会话，不建第二窗格，也不生成缺失文件占位 |

主菜单单独打开画布、画布内全部编辑功能、文件格式与已有 `.xcanvas` 均不变。

## 保留与后续

- `InfiniteEditor.surfacesForAttach()` 跨宿主替换（`c4f30567`）保留：首次挂载不替换，画布不再分屏后正常路径不触发。待真机确认没有其他跨宿主重挂路径后，再单独清理；不把本次改动记为黑区已解决。
- `ToolbarLayout` 中笔记 / PDF 与画布混合工作区的分类、`workspaceToolbarLayout` 合并画布附加项及插入空白移到名称栏的分支已无法到达，本轮未删除，留待后续清理并同步测试。
- 当前 `codex/mind-map` 是独立 `.xmap` 节点编辑器（文本／手绘节点、关系线），尚无图片或节点外自由笔迹，**还不是画布的超集**；「超集」是后续目标，不是已有能力。
- `codex/render-lifecycle` 的 P1 / P4 中专为跨窗格画布宿主与输入交接所做的部分，需由用户在该分支决定缩减范围。

## 验证

- `JAVA_HOME=…/jdk-21.jdk ./gradlew --offline -I tmp/outdir.init.gradle --project-cache-dir /private/tmp/xnotes-canvas-no-split-cache :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest`，构建输出在 `/private/tmp/xnotes-canvas-no-split-build/`：通过，JVM 2755 项，0 失败、0 错误、0 跳过。
- `SplitEditorTest`：原「笔记 + 画布」保存恢复用例改为两份笔记，继续覆盖顺序、比例、方向与双视口；新增 `savedSplitWithCanvasIsRefusedWithoutDetaching` 与 `lastSessionSplitWithCanvasRestoresOnlyThePrimaryPane`。仅编译，未在模拟器或设备上运行。
- 后续（同日）：master test2 模拟器仪器测试中 `savedSplitReopensFilesOrderRatioAndBothViewports` 在 `saveCurrentSplit()` 断言失败（见 `master-test2-2026-11-07.md`）。原因是上述改写把两份笔记写成同一文档，文档 ID 重复，第二窗格按重复身份被拒绝打开，分屏不成立。夹具已改为第二份写入独立的 `Document.blank()`；属测试问题，非产品缺陷。修正仅编译，待模拟器重跑确认。
- MainActivity 入口拦截（按钮隐藏、窗格首页拒绝、两文件分屏、在旁边打开）没有自动化测试，需手动或真机确认。
- 待真机验收：MovinkPad Pro 14 / Android 15 上，单独打开画布正常；上述各入口无法让画布进入分屏且提示正确；旧含画布 `.xsplit` 与上次会话恢复行为；笔记 / PDF 分屏回归。
