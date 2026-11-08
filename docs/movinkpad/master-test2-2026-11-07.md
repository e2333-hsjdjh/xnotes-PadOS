# 主线独立测试版 test2（2026-11-07）

用户要求：拉取更新并打包测试版 APK，不覆盖正式版，自动将打包日志推送远端。
状态：打包成功，2779 项 JVM 测试通过；Android 重跑 121 项、2 项失败，目标设备验收待执行。
本轮边界：本地交付独立测试 APK，推送文档和日志，不发布 GitHub Release、不上传 APK、不安装用户设备。

- 源码从 `b5175e8e` 快进到 `eed318ac`。正式 versionCode 保持 98；独立工作树 `tmp/master-test2/source` 固定在源码提交，不修改产品代码。
- 测试身份 `com.xnotes.movinkpad.branchtest.master`，versionCode 2，标签 `XNotes master 测试版`；与正式包 `com.xnotes.movinkpad` 并存。已有主线 test1 则升级为 test2，各安装身份的数据与权限独立，正式版笔记请通过应用导出便携包后导入测试版。
- 更新包含无限画布取消分屏、稳定工具栏／窗格内容槽、渲染生命周期诊断与 Surface 捕获。诊断 D-1 至 D-7 待修项按原记录保留，本次打包不顺带修正。
- JDK 21 / Gradle 8.13 / AGP 8.12.3 / SDK 36 / Build Tools 36.0.0 / NDK 27.0.12077973 / CMake 3.22.1。ARM64 Debug 测试版，不是压缩 Release 构建；沿用原签名，不使用 IDE ABI 注入。
- ARM64 构建在独立工作树运行 `./gradlew --offline --no-daemon --max-workers=2 --project-cache-dir tmp/gradle-arm64 -I ../build.init.gradle -I <主工作区>/scripts/branch-preview.init.gradle -PbranchTestId=master '-PbranchTestLabel=XNotes master 测试版' -PbranchTestVersion=2 :app:testDebugUnitTest :app:assembleDebug`。
- 模拟器使用独立 `tmp/gradle-emulator` 项目缓存、`build/emulator-app/` 与 `build/emulator-cxx/`，附加主工作区 `tmp/branch-tests/emulator.init.gradle`，仅 x86_64；运行 `:app:assembleDebug :app:assembleDebugAndroidTest`。
- 初次准备因主工作区没有 local.properties 导致初始化脚本尚未生成、构建未启动。已在隔离工作树写入 SDK 路径并生成初始化脚本后重试，未改业务源码；失败日志在本机 tmp/master-test2/setup-failed-*.log 保留。
- 日志归档位于 [build-logs/master-test2](build-logs/master-test2/build.txt)，替换本机路径、清理行尾空白；完整 XML 与 APK 核验资料保留在本地 `build/branch-tests/master-test2/`。
- 原有三处用户本地修改单独保护并恢复，不纳入提交。未运行完整上游 lint、真实 AI 供应商或 MovinkPad 真机验收。模拟器窗口与输入测试不代表目标 GPU、原黑块、真实前缓冲效果或笔延迟通过。

## 最终结果与未解决项

- ARM64 构建成功（3 分 8 秒），2779 项 Debug JVM 测试通过，失败/错误/跳过均为 0；模拟器构建成功（2 分 42 秒）。
- Android 15 / API 35 x86_64、SwiftShader、1600×1000 本机模拟器：首次 121 项、7 项失败；发现 System UI 无响应弹窗占据焦点。关闭该弹窗、确认 RenderHostTestActivity 获得焦点并清理测试包数据后重跑：121 项、2 项失败（119 项通过），不标为全量通过。
- 仍失败：`PaneChromeHostTest.floatingToolbarInterceptsWindowStylusBeforeNativeContent` 在 `injectWindowStylus` 发生目标窗口 UID 不匹配的输入注入异常；`SplitEditorTest.savedSplitReopensFilesOrderRatioAndBothViewports` 在 `saveCurrentSplit()` 返回值断言失败。尚未确定是测试夹具还是产品问题，本轮不修改源码来绕过失败。
- 后续诊断（同日，Claude Code）：`SplitEditorTest` 失败为测试夹具问题——画布取消分屏时该用例改为两份笔记，但两份写入同一文档，文档 ID 重复，第二窗格按重复身份被拒绝打开，分屏不成立。夹具已改为第二份写入独立 `Document.blank()`（`dcfc2eb9`），仅编译，待模拟器重跑。`PaneChromeHostTest` 的 UID 不匹配疑为焦点被其他窗口占用的环境问题，未确认。
- 失败及重跑日志见 [首次](build-logs/master-test2/instrumentation-first.txt)、[最终](build-logs/master-test2/instrumentation.txt)。之前的窗口超时和截图像素断言在重跑中未再出现，不据此声称真机 GPU 验收通过。
- 实际 APK `com.xnotes.movinkpad.branchtest.master` / `0.9.8-test-master-2` / versionCode 2，ARM64 Debug（debuggable），无 testOnly；与正式版包名不同，可并存。与主线 test1 原签名相同，证书 SHA-256 `a0453b7be6c0c8239e8616208612c412cf501da8898c7a0450cc923c8e358451`，验签和 16 KiB ZIP 页对齐通过。
- 文件 `build/branch-tests/master-test2/XNotes-master-eed318ac-test2-arm64.apk`，53,894,284 字节；SHA-256 `3ad2cc3aad8f2076aa92246312f89620e05b1bac021a9300b5bf738d4343acf1`。
