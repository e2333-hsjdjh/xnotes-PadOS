# XNotes for iOS

独立的 SwiftUI 工程，最低部署版本为 iOS / iPadOS 17。打开 `XNotes.xcodeproj`，选择 XNotes scheme 和 iPad 模拟器运行。应用使用 Apple 自带的 PencilKit 和 PDFKit。

| 目录 | 职责 |
| --- | --- |
| `XNotes/App` | 应用入口、外观 |
| `XNotes/Library` | 文档库、查找、回收站、导入与导出 |
| `XNotes/Editor` | 页面、笔迹、工具栏和图层 |
| `XNotes/Storage` | 文档模型、原子写入、PDF 原件和备份 |
| `Configuration` | 应用文件类型声明 |

## Android 对照与当前进度

| Android 源码范围 | iOS 当前状态 | 待对齐 |
| --- | --- | --- |
| `ExplorerView`、`NewNoteScreen`、`TrashPane`、`SearchTab` | 文档列表、嵌套文件夹树与移动、文档移动、多选批量操作与合并导出 PDF 和批量备份、标题和正文搜索、创建、重命名、回收站、列表与带封面预览的网格、名称、时间与大小排序 | 整树备份 |
| `Editor`、`Toolbar`、`SidePanel` | 分页、带缩略图的页面列表、正文搜索和命名书签、插入空白页或选定 PDF 页面、插页原文搜索、标注和缺失底稿重新关联、多选复制与剪切、跨文档粘贴、删除与 PNG 导出、排序笔记页、可设颜色与粗细的笔和荧光笔、整笔与局部橡皮、套索、跨页及图层的编辑撤销重做、双指缩放、适合宽度、上次页码与缩放恢复、内置空白、横线、方格、点阵模板、逐页与批量纸张尺寸、方向和模板调整，基础文字和从照片图库或文件插入图片、移动、缩放、裁剪与删除 | 模板参数、导入模板、封面与视图设置、富文本、图层内对象混排、其他插入与编辑工具、工具栏自定义 |
| `InfiniteEditor`、`InfiniteToolbar` | 2048 × 2048 可滚动书写区域、按内容裁切导出 PDF | 无限坐标、路径点、导航缩略图和画布专用工具 |
| `PdfResources`、`PdfMarkups`、`BundlePdf` | PDF 导入、分页显示、原文查找、匹配位置高亮与滚动定位、目录跳转、从搜索结果添加可编辑文字标注及标准 PDF 文字注释导出、笔迹叠加、按 SHA-256 共享原件、按指纹重新关联原件、另存仅批注副本、包含原件的 iOS 备份、导出整份或所选页面的 PDF，保留可见笔迹与页面内容 | 自由选区文字标注、批处理、PDF 链接与交互对象保留、扫描 PDF 性能验证 |
| `LayersPanel` | 侧栏、每页图层、选层、增删、命名、显隐、锁定、单层缩略图、菜单排序和编辑模式拖动排序 | 直接拖动排序、跨图层移动、焦点交接后的历史 |
| `SplitDocumentPicker`、`PaneHomeState` | 两份文档并排、同一分页笔记或 PDF 的只读副视图、窗格内文件夹浏览与文件选择、主动恢复上次文档组合和独立页码与缩放 | 同文档编辑焦点接管、共享工具栏和焦点路由、精确滚动位置恢复 |
| `AskAi`、`AiSessions`、`PreferencesPane` | 外观切换 | AI 会话、供应商、Keychain、模型工具、截图和 PDF 附件，其他设置及帮助页面 |
| `pen/`、`PenShortcutSettings` | PencilKit 输入、可切换仅 Apple Pencil | iPad 可用手势映射、真机压感、延迟、防误触与取消验收 |
| `DocumentCodec`、`CanvasCodec`、`BundleIdentity` | iOS 版本化 `.xnotes` 备份 | Android `.xnote` 格式互通、旧版迁移及身份恢复 |

PDF 原件和批注分开保存。导出备份包含两者。当前回收站支持单份或批量永久删除，可设置保留期限，无引用且未打开的 PDF 原件经过 7 天宽限后可自动回收。

上游授权见仓库根目录的 [MIT 许可证](../LICENSE)。
