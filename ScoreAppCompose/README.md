# 乐谱管理 · Compose Multiplatform 安卓工程

一个古典乐谱个人曲库管理应用。工程可在**纯命令行**下打包，不需要安装 Android Studio。

> 本工程是对既有应用业务逻辑的**独立重写**：只参考了原应用的数据结构、交互语义与设计令牌，
> 所有实现代码均为重新编写，未复制任何反编译产物。

---

## 一、快速开始

### 环境要求

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 17 | Gradle 与 AGP 的运行前提 |
| Android SDK | Platform 35 + Build-Tools 35 | `compileSdk = 35` |
| Gradle | 8.10.2 | 已内置 wrapper，首次运行自动下载 |

### 打包

```bash
# Windows
build.bat

# macOS / Linux / Git Bash
./build.sh
```

脚本会自动完成三件事：定位 JDK 与 Android SDK、生成 `local.properties`、调用 `gradlew` 构建。
两个脚本都接受一个可选参数作为 Gradle 任务名：

```bash
build.bat assembleRelease     # Windows
./build.sh assembleRelease    # macOS / Linux / Git Bash
```

也可以直接使用 Gradle：

```bash
./gradlew :composeApp:assembleDebug     # 产物：composeApp/build/outputs/apk/debug/composeApp-debug.apk
./gradlew :composeApp:assembleRelease   # 未签名 Release 包
./gradlew :composeApp:clean             # 清理
```

### 安装

```bash
adb install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

### SDK 路径

`local.properties` 已被 `.gitignore` 忽略，由构建脚本自动生成。若需手工指定：

```properties
sdk.dir=C\:\\Users\\<用户名>\\AppData\\Local\\Android\\Sdk
```

### Gradle 缓存位置

脚本使用 Gradle 的默认缓存（`~/.gradle`），首次构建会下载 Gradle 分发包与全部依赖。

如果机器上已经有一份预置好的 Gradle 缓存（例如为离线环境准备的），
用 `GRADLE_USER_HOME` 指过去即可，必要时再加 `--offline` 强制不联网：

```bash
# Git Bash / macOS / Linux —— 路径必须是 Windows 风格，用 /c/... 会被 MSYS 改写
export GRADLE_USER_HOME="C:\\Users\\<用户名>\\gradle_home"
./gradlew :composeApp:assembleDebug --offline
```

```bat
REM Windows
set GRADLE_USER_HOME=C:\Users\<用户名>\gradle_home
gradlew.bat :composeApp:assembleDebug --offline
```

缓存里必须包含 AGP 8.7.3 与 Compose Multiplatform 1.8.2，否则会报
`Plugin [id: 'com.android.application', version: '8.7.3'] was not found`。

---

## 二、功能范围

应用围绕「个人古典乐谱库」展开，包含三个主页面与一组底部弹层。

### 乐谱库（主页面）

- **双子页签**：`乐谱库` / `合集`
- **搜索**：对标题、作曲家、体裁、乐器、时期、来源六个字段做不区分大小写的子串匹配
  （占位文案沿用原应用：`搜索曲名、作曲家、类型、乐器…`）
- **分组**：按作曲家 / 曲目类型 / 乐器三种维度分组，组内数量降序、数量相同按名称升序
- **筛选**：三维度分面筛选，维度内取并集、维度间取交集
- **视图切换**：列表 / 双列网格

### 筛选与分组弹层（暂存式交互）

所有改动先写入**暂存态**，点「查看 N 个结果」才提交，提交后提示「已筛选出 N 首乐谱」：

- 每个分面标签上显示**在当前暂存条件下的命中数**
- 同维度已选条件不参与该维度的计数，否则多选后同维度其它选项会全部归零
- 命中数为 0 且未选中的选项自动降低不透明度并禁用
- 支持逐维度清空与整体重置
- 「分组方式」小节带副标题 `决定列表如何归类`

### 详情页

结构对齐原应用：顶栏只有返回与「更多」，正文依次是封面卡片、强调色「分享」主按钮、
`元数据` 面板、`分类归属` 面板。

- 封面右上角叠加「编辑」角标，不占用正文空间
- 头部元信息行为 `曲目类型 · 乐器 · 时期 / 风格 · 难度`，空白字段自动剔除
- `元数据` 面板 8 行：作曲家 / 曲目类型 / 乐器 / 时期 · 风格 / 难度 / 来源 / 页数 / 添加时间
- 页数为 0 时显示 `—`；添加时间由 `domain.formatDate` 格式化为 `yyyy-MM-dd HH:mm`
- `分类归属` 面板列出该乐谱所属的谱单（`domain.setsOfScore` 反查），每行可点开谱单；未入谱单时显示 `尚未加入任何谱单`
- 「更多」收拢打开乐谱与删除两个次级动作；删除为两段式确认（首点切换为「确认删除」，再点才执行）

### 作曲家

- 按索引字母分节，右侧 A-Z 快速跳转（按 `LazyListState` 锚点下标定位）
- 字母表头右侧显示该字母下的作曲家数量（`N 位`）
- 行内展示姓氏（主）+ 全名（次）+ 作品数徽标
- 头像底色由姓名哈希稳定推导（固定饱和度/明度，视觉重量均匀）
- 姓名按中点 `·` 切分取姓氏展示；索引优先走人工字母表，未命中时退化为首字符（汉字视为字母），非字母归入 `#`
- 点击某位作曲家进入**作品页**

### 作品页

- 顶栏是固定标题 `作品`，作曲家身份放在下方抬头块（头像 + 姓氏 + 全名 + `N 首作品`）
- 只列出这位作曲家的乐谱，按标题升序
- 空态显示 `暂无作品`

### 我的

- 曲库统计（乐谱数 / 谱单数 / 作曲家数）全部实时推导，不做缓存
- 元数据明细（作曲家 `N 位` / 曲目类型 `N 类` / 乐器 `N 种` 去重计数）
- 设置项列表：导入与存储 / 自动识别元数据 / 清理缓存

### 弹层

| 弹层 | 说明 |
| --- | --- |
| 筛选与分组 | 分面筛选 + 分组维度选择 |
| 导入乐谱 | 相册图片合成 PDF / 直接导入 PDF 两条路径 |
| 编辑乐谱 | 8 个字段，带常用取值建议，支持删除 |
| 排序方式 | 四种排序模式（**扩展功能**，见下） |
| 谱单详情 | 列出谱单成员，可打开谱单 |
| 更多 | 打开乐谱 / 编辑元数据 / 从乐谱库删除 |

### 封面：文件首页优先，程序化绘制兜底

封面缩略图分三条路径，与 HTML 原型同口径：

1. **有实体文件且能渲染** → 取**文件首页**当封面。
   - PDF：`PdfRenderer` 取第 0 页，按 `min(640 / 页宽, 1.4f)` 缩放，
     `ARGB_8888` + `eraseColor(-1)` 铺白底后 `render(..., RENDER_MODE_FOR_DISPLAY)`
   - 非 `.pdf`（相册导入的图）：`BitmapFactory` 普通位图解码
   - 缓存：`CoverRenderer` 内部的 `LruCache<String, ImageBitmap>(12 * 1024 * 1024)`，
     `sizeOf = width * 4 * height`（**刻意**不用 `Bitmap.byteCount`，与原应用一致）。
     *没有*独立的 `ThumbCache` 类型——它就是这个 object 的私有字段
2. **有实体文件但渲染不出来** → 叠「预览加载失败」提示层
   （底 `#EFEFF2`、图标 `#B4B4BC`、文字 `#8A8A93` @ 9sp Medium）
3. **没有实体文件** → 退回程序化绘制

程序化绘制**是兜底，不是主路径**。它不依赖任何图片资源，由 `thumbSeed` 驱动
确定性随机数实时绘制：

- `engrave`（默认）：雕版乐谱页 —— 若干谱表系统，每系统 5 条谱线 + 符头 + 符干 + 小节线
- `cover`：唱片封面 —— 渐变底 + 同心弧装饰 + 标题/副标题/色带排版

同一份乐谱在任何设备、任何尺寸下都会渲染出同一张图（`mulberry32` 纯整数运算，跨平台一致）。

> **两处缓存策略不一致是刻意的。** 封面按路径缓存、有固定 12 MB 预算、
> 按 `宽×4×高` 估体积；阅读器按页序缓存、上限取 `maxMemory()/8`、
> 按 `Bitmap.byteCount` 估。这是原应用本来的样子，且各有道理（封面数量多、
> 单张尺寸固定；阅读器一份文档内页数有限、单页更大）。**统一它们会改变内存行为，
> 不是重构该顺手做的事。**

---

## 二·五、与原应用的差异清单

以下项目**不是**从反编译产物还原的，而是为了让交付物可运行、可评审而做的取舍。
逐条列出以便区分「还原」与「扩展」。

### 样例数据被放大

原应用只预置 **1 首乐谱 + 1 个谱单**：

```kotlin
Score("《升c小调第十四钢琴奏鸣曲「月光」》Op.27 No.2", "路德维希·范·贝多芬",
      "奏鸣曲", "钢琴", "古典", "高级", "Henle 原版", pages = 14,
      assetPdf = "moonlight_op27_no2.pdf", thumbSeed = 37, thumbRows = 5)
ScoreSet("独奏会备选曲目", "贝多芬奏鸣曲，含慢板乐章与终曲", listOf(37))
```

本工程把首条原样保留，另补 **41 首乐谱 + 4 个谱单**用于演示筛选、分组、
A-Z 索引与统计。相应地，姓氏别名表也从原应用的 15 条扩展到 23 条
（新增条目已用注释标注），否则这些作曲家的索引字母会退化成汉字，
A-Z 索引条上就会出现中文。

### 「排序」是扩展功能

原应用**没有排序功能**——反编译产物中不存在 `SortMode` 之类的枚举，
也没有任何「排序」相关文案。`排序方式` 弹层与四种排序模式是本工程新增的。

### 编辑弹层只用于修改

原应用的「编辑乐谱」弹层只服务于修改已有乐谱，新增走「导入乐谱」流程
（相册图片合成 PDF 或直接导入 PDF）。因此：

- 弹层标题固定为 `编辑乐谱`，没有「新建乐谱」分支
- 悬浮按钮打开的是**导入乐谱**弹层

### 三处原应用瑕疵已改正

反编译证据显示原应用本身有三处不干净。本工程**全部改正**，并抽成可测的纯逻辑
（`domain/ReaderBarText.kt`、`ScoreDraft.normalized`）由 `commonTest` 锁住：

| # | 原应用现象 | 本工程处置 |
| --- | --- | --- |
| ① | 导入回调与保存回调两个调用点，都把 `未编目`（语义属「曲目类型」）填进了**作曲家**字段，后面每个占位值顺移一格 | 按语义归位。写成显式语义表：作曲家→`佚名`、类型→`未编目`、乐器→`未分类`、时期→`未指定`、难度→`—`、来源→`本地导入`。不再靠「位置对齐」隐式推断 |
| ② | 阅读器顶栏的「共 N 页」副标题与「N 页」徽标**重复** | `ReaderBarText.subtitle()` 只在就绪态报页数，`badgeText()` 独立保留（维持原视觉）；两者口径一致 |
| ③ | `pageCount == 0` 时副标题仍是「读取中…」，与正文「这份 PDF 没有任何页面」**并存**——顶栏说还在读、正文说读完了 | 把状态显式建模成 `ReaderPhase` 四态（`Loading` / `Failed` / `Empty` / `Ready`），矛盾组合在类型上就不存在。空文档报「没有页面」 |

第 ① 处还纠正了一个上游误读：原应用两个导入调用点写的都是 `本地导入`
（`MainActivityKt` 两处调用点均如此），曾误加过一个 `相册导入` 取值 ——
那会把同一个来源拆成两个筛选面，已移除。

### 字段归一化

保存与导入共用同一套归一化口径（`ScoreDraft.normalized`），
空字段填固定占位值：作曲家 → `佚名`、曲目类型 → `未编目`、乐器 → `未分类`、
时期 → `未指定`、难度 → `—`、来源 → `本地导入`，页数解析失败则保留原值。

### 仍为演示值的部分

以下几处两侧都还是固定演示数值，不反映真实设备状态：

- 「我的 → 导入与存储」提示固定 128 MB
- 「我的 → 清理缓存」只提示「已清理 24 MB」
- 崩溃提示弹层是硬编码文案（崩溃日志落盘未实现）

---

## 二·六、文件与系统能力

这一层现在是**真实接通**的（不再是「只给提示文案」）：

| 能力 | 实现 |
| --- | --- |
| 相册多图 → A4 PDF | `GetMultipleContents` → `ImportUtil.imagesToPdf`：`PdfDocument` 逐页 595×842 pt 居中排版，图片最长边压到 1400 px、`RGB_565` 解码 |
| 选 PDF 导入 | `GetContent` → `ImportUtil.copyPdfToLocal` 真拷贝到 `files/scores/` |
| 封面渲染 | `CoverRenderer`（内含 12 MB `LruCache`），见上文 |
| PDF 阅读器 | `PdfDocState`（`PdfRenderer`）+ `PdfViewerScreen`：翻页、缩放 1–4×、四态 |
| 分享 | `ShareUtil`：有文件走 `FileProvider` + `ACTION_SEND` + `EXTRA_STREAM`；无文件走纯文本 |
| 内置乐谱安装 | `PdfAssets`：启动时把 `assets/scores/*.pdf` 拷进 `files/scores/`，并把样例乐谱的 `assetPdf` 解析成真实绝对路径 |

平台类型（`Context` / `Uri` / `Bitmap` / `android.graphics.pdf.*`）全部被
`expect/actual` 挡在 `commonMain` 之外，接口在 `util/FileBridge.kt`。

> **注意**：仍**没有**崩溃日志落盘与「上次运行发生异常」提示。

### 内置乐谱 PDF：资产与版权处理

样例曲库第一条乐谱声明了 `assetPdf = "moonlight_op27_no2.pdf"`，这份文件来自
原应用反编译产物，是**第三方版权扫描件**。因此：

- **不入版本库。** 仓库根 `.gitignore` 排除了 `decoded_resources/` 与所有 `*.pdf`，
  新克隆下来的仓库里没有它。
- **构建时从本地注入。** `composeApp/build.gradle.kts` 里的 `injectBundledPdf` 任务
  在 `decoded_resources/assets/scores/*.pdf` **存在时**才复制进 APK 的
  `assets/scores/`，目录走 `build/injectedAssets/`，不污染工作区。
  只挂在 **debug 变体**上——release 包不该意外带上版权内容。
- **缺失是正常状态，不是错误。** `PdfAssets.installMissing` 在
  `assets.list("scores")` 为空时返回**空映射**而非抛异常，`installBundledScores`
  原样返回曲库。此时内置乐谱的 `filePath` 保持为 null，封面自动退化为
  程序化绘制、「打开乐谱」提示「这份乐谱还没有 PDF 文件」——功能不缺，只是少了预览图。

> **一个踩过的坑**：AGP 把 assets 源目录的**根**映射到 APK 的 `assets/`。
> 若把 `assets.srcDir` 指到 `injectedAssets/scores`，文件会落到
> `assets/xxx.pdf`，而 `installMissing` 读的是 `assets.list("scores")`，
> 永远找不到——APK 里明明有文件、运行时却当没有。`srcDir` 必须指向
> `injectedAssets`（其下再放 `scores/` 子目录）。

「拷完怎么算路径」那半是纯逻辑，住在 `domain/BundledScoreResolver.kt`，
由 `commonTest` 覆盖（命中的优先级、唯一候选兜底、多份不匹配时返回 null、
已有 `filePath` 不被覆盖、空安装映射原样返回）。`PdfAssets` 只做转发，
不重复实现一遍规则。


### 封面版式参数（原为未接线状态）

`Score` 的 `coverTx` / `coverTy` / `coverTSize` / `coverTGap` / `coverSubX` /
`coverSubY` / `coverDeco` / `coverFooter` 八个字段此前**只定义、从未被读取**，
两套实现都改用 `h * 0.44f` 之类的百分比常量绘制，导致样例数据里调好的排版
完全没有生效。现已改为真实读取，几个踩过的坑记录如下：

- **坐标是「左对齐」语义，不是中心点。** 样例数据里 `coverTx=15` 与
  `coverSubX=15` 并存，而 `coverSubY=120` 已接近画布底边（参考系 100）。
  若按中心点解释，`tx=15` 会让长标题左半截跑出画布——实测 `DEBUSSY` 被裁成
  `EBUSSY`。按左边界解释后两个 `15` 才互相自洽。
- **`coverSubY=120` 超出参考系。** 换算后落在画布下方被裁掉，因此夹到 96。
- **参考画布是 100×100。** 参数按该基准标定，绘制时按实际尺寸等比换算；
  旧常量 `h*0.90` 在 100 高下等价于 90，换算后与之一致，不会出现版式突变。
- **字号只跟横向缩放走。** 用纵向系数会让窄卡片的字缩到不可读。
- **长文本需要缩字 + 截断兜底。** 否则 `PIANO WORKS · UR TEXT` 这类长串会直接溢出。

### 元数据统计行

「我的」页三行统计（作曲家 / 曲目类型 / 乐器）此前挂在 `onClick = {}` 上——
可点、有涟漪，但什么都不发生。现改为跳到乐谱库并按该维度筛选：作曲家进全局
作曲家视图，「曲目类型」「乐器」取当前曲库中**出现频次最高**的取值作为切入点
（比取字典序第一个更符合「先看最主流的」预期）。曲库为空时回退为「只跳库不筛选」。

---

## 三、工程结构

```
ScoreAppCompose/
├── build.sh / build.bat          一键命令行打包
├── settings.gradle.kts
├── build.gradle.kts              根构建脚本（仅声明插件版本）
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml        版本目录（统一管理依赖版本）
│   └── wrapper/                  Gradle wrapper
└── composeApp/
    ├── build.gradle.kts
    └── src/
        ├── commonMain/kotlin/com/example/scoreapp/
        │   ├── model/            领域模型
        │   │   ├── Score.kt          乐谱（含渲染描述字段）
        │   │   ├── ScoreSet.kt       谱单
        │   │   ├── FilterState.kt    筛选条件 / 维度 / 排序方式
        │   │   └── Screen.kt         导航目标与底部页签
        │   ├── domain/           纯业务逻辑（无 UI 依赖）
        │   │   ├── LibraryQuery.kt   搜索 / 筛选 / 分组 / 分面计数
        │   │   ├── ComposerNames.kt  姓名切分、字母索引、别名表
        │   │   ├── ScoreMeta.kt      日期格式化、谱单成员解析与归属反查
        │   │   ├── AvatarPalette.kt  头像配色推导
        │   │   ├── ShareSummary.kt   分享正文拼装（纯字符串，可测）
        │   │   ├── ReaderBarText.kt  阅读器顶栏文案四态（纯函数，可测）
        │   │   └── BundledScoreResolver.kt  内置乐谱路径解析（纯函数，可测）
        │   ├── data/
        │   │   └── SampleLibrary.kt  内置样例曲库与表单选项
        │   ├── util/
        │   │   ├── Platform.kt       expect 声明（时间戳），androidMain 提供 actual
        │   │   └── FileBridge.kt     expect 契约：导入 / 分享 / 封面渲染
        │   └── ui/
        │       ├── ScoreAppState.kt  状态中枢 + 编辑草稿
        │       ├── AppRoot.kt        根骨架（导航 / 弹层 / 提示 / 阅读器宿主）
        │       ├── theme/            设计令牌与主题
        │       ├── components/       通用组件、程序化缩略图、PDF 阅读器
        │       │   ├── ScoreThumb.kt     封面三态（位图 / 失败提示 / 程序化绘制）
        │       │   ├── PdfDocState.kt    PdfRenderer 封装 + 四态 ReaderPhase
        │       │   └── PdfViewerScreen.kt 阅读器界面（翻页 / 缩放）
        │       ├── screens/          五个页面（乐谱库 / 作曲家 / 作品 / 详情 / 我的）
        │       └── sheets/           六个底部弹层（含「更多」）
        ├── commonTest/kotlin/com/example/scoreapp/
        │   ├── LibraryQueryTest.kt   查询 / 筛选 / 分组 / 分面 / 姓名索引
        │   ├── DraftAndMetaTest.kt   保存归一化 / 详情页元信息行
        │   ├── ScoreMetaTest.kt      日期格式化 / 谱单成员解析 / 归属反查
        │   ├── FileLinkTest.kt       导入口径 / 分享摘要 / 阅读器四态 / 占位符语义
        │   └── BundledScoreResolverTest.kt  内置乐谱路径解析（含「没有内置 PDF」降级）
        └── androidMain/
            ├── AndroidManifest.xml   权限 + FileProvider
            ├── kotlin/.../MainActivity.kt  选择器 / 权限 / 返回键 / 内置乐谱安装
            ├── kotlin/.../util/
            │   ├── Platform.android.kt      nowMillis 的 actual
            │   ├── ImportUtil.kt            图片→A4 PDF、PDF 拷贝、页数
            │   ├── ShareUtil.kt             分享意图与文件名
            │   ├── CoverRenderer.kt         首页渲染 + 12 MB 缓存
            │   ├── PdfAssets.kt             assets→filesDir 安装（幂等，缺失时降级）
            │   └── FileBridge.android.kt    bridge 的 actual 实现
            └── res/
                ├── values/                  主题与颜色资源
                └── xml/file_paths.xml       FileProvider 路径白名单
```

### 分层约定

- `model` / `domain` / `data` 是**平台无关**的纯 Kotlin，可单独测试，也可直接复用到其它 target
- `ui` 只做状态到界面的映射；派生数据（可见列表、分组、分面计数）一律即时计算，避免缓存不一致
- 平台能力一律走 `expect` / `actual`，`commonMain` 中不出现 `java.*` 与 `android.*`
  （`Context` / `Uri` / `Bitmap` / `android.graphics.pdf.*` 全被 `FileBridge` 挡住）
- 唯一依赖 Android 的代码是 `androidMain` 下的 `MainActivity`、`util/*.android.kt` 与 `res/` 资源

### 关于编译选项

`composeApp/build.gradle.kts` 里对全部 sourceSet 统一开启了 `ExperimentalMaterial3Api` /
`ExperimentalLayoutApi` / `ExperimentalComposeUiApi` 的 opt-in，避免在每个 Composable 上重复标注。

另外 `SheetScaffold` 刻意**不把 `SheetState` 暴露到参数列表**——该类型属于
`ExperimentalMaterial3Api`，一旦出现在公开签名里，opt-in 要求会传播给所有调用方，
每个弹层都得重复写 `@OptIn`。弹层状态在骨架内部创建即可。

### 关于图标

`material-icons-extended` 自 Compose Multiplatform 1.8 起不再随版本发布，
因此 `ui/components/AppIcons.kt` 用 **SVG 路径数据在运行时构建 `ImageVector`**，
不引入任何额外依赖，也便于统一线宽与圆角风格。

---

## 四、技术栈

| 组件 | 版本 |
| --- | --- |
| Kotlin Multiplatform | 2.1.21 |
| Compose Multiplatform | 1.8.2 |
| Android Gradle Plugin | 8.7.3 |
| Gradle | 8.10.2 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 24 |
| Java / JVM Target | 11 |

### 扩展到其它平台

当前只配置了 `androidTarget()`。因为 `commonMain` 中没有任何 Android API 调用，
要增加 iOS 或 Desktop 支持，只需在 `composeApp/build.gradle.kts` 的 `kotlin { }` 块中
补充对应 target，并把 `MainActivity` 中的返回键处理替换为各平台等价实现。

---

## 五、与原型的关系

同目录外的 `prototype/scoreapp-prototype.html` 是同一套业务逻辑与视觉规范的
**纯 HTML + 原生 JS 交互原型**，可直接用浏览器打开。两者共享：

- 相同的设计令牌（颜色、圆角、字号）
- 相同的样例曲库（42 首乐谱 / 5 个谱单）
- 相同的筛选 / 分组 / 分面计数语义
- 相同的封面三态与缓存口径（首页渲染 / 失败提示 / 程序化兜底）
- 相同的阅读器四态文案（`ReaderBarText` ↔ `readerSubtitle` / `readerBadge`）
- 相同的导入占位符语义表与分享摘要拼装
- 相同的页面与弹层结构、相同的界面文案

原型用于快速确认交互与视觉，本工程用于产出真实安装包。

> **改一方的业务规则或样例数据，必须同步另一方。** 下面两条测试路径就是用来
> 锁住它们不各自漂移的。

### 两条实现路径的回归测试

同一组业务语义在两处各有一份断言，用来锁住「HTML 原型」与「Compose 工程」
不会各自漂移：

```bash
# 原型：200 项断言（最小 DOM 桩加载原型脚本）
node prototype/regression-test.js

# 工程：业务层单元测试（commonTest，不依赖 Android 运行时）
cd ScoreAppCompose
./gradlew :composeApp:testDebugUnitTest      # 78 项用例，报告：composeApp/build/reports/tests/
```

覆盖范围：搜索匹配、筛选并集/交集、分面计数（同维度不归零 / 跨维度约束）、
分组排序、姓名切分与 A-Z 索引、头像配色、谱单种子解析、保存时的字段归一化、
详情页元信息行拼接、导入占位符语义、分享摘要拼装、阅读器顶栏四态文案、
封面三态与缓存口径、内置乐谱路径解析与「没有内置 PDF」的降级路径。
