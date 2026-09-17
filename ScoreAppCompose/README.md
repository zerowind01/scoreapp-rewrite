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

### 程序化缩略图

列表缩略图**不依赖任何图片资源**，由 `thumbSeed` 驱动确定性随机数在 Canvas 上实时绘制：

- `engrave`（默认）：雕版乐谱页 —— 若干谱表系统，每系统 5 条谱线 + 符头 + 符干 + 小节线
- `cover`：唱片封面 —— 渐变底 + 同心弧装饰 + 标题/副标题/色带排版

同一份乐谱在任何设备、任何尺寸下都会渲染出同一张图（`mulberry32` 纯整数运算，跨平台一致）。

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

### 字段归一化中的一处加固

原应用保存时对空字段填入固定占位值：曲目类型 → `未编目`、乐器 → `未分类`、
时期 → `未指定`、难度 → `—`、来源 → `本地导入`，页数解析失败则保留原值。
**作曲家字段原应用不做兜底**，本工程补了 `佚名` 兜底，避免作曲家索引出现空行。

### 未实现的部分

原应用涉及文件与系统能力的链路不在本次范围内，因为原型无法在浏览器里执行、
本工程也不引入文件 IO：

- PDF 阅读器（`PdfViewerScreen`）、页面渲染与缩放
- 相册选图 → 合成 PDF、PDF 导入与本地拷贝
- 分享（`ShareUtil` 的文本拼装与 `Intent.createChooser`）
- 崩溃日志落盘与「上次运行发生异常」提示
- 真实缩略图解码（本工程改为程序化绘制，见上）

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
        │   │   └── AvatarPalette.kt  头像配色推导
        │   ├── data/
        │   │   └── SampleLibrary.kt  内置样例曲库与表单选项
        │   ├── util/
        │   │   └── Platform.kt       expect 声明（时间戳），androidMain 提供 actual
        │   └── ui/
        │       ├── ScoreAppState.kt  状态中枢 + 编辑草稿
        │       ├── AppRoot.kt        根骨架（导航 / 弹层 / 提示）
        │       ├── theme/            设计令牌与主题
        │       ├── components/       通用组件与程序化缩略图
        │       ├── screens/          五个页面（乐谱库 / 作曲家 / 作品 / 详情 / 我的）
        │       └── sheets/           六个底部弹层（含「更多」）
        ├── commonTest/kotlin/com/example/scoreapp/
        │   ├── LibraryQueryTest.kt   查询 / 筛选 / 分组 / 分面 / 姓名索引
        │   ├── DraftAndMetaTest.kt   保存归一化 / 详情页元信息行
        │   └── ScoreMetaTest.kt      日期格式化 / 谱单成员解析 / 归属反查
        └── androidMain/
            ├── AndroidManifest.xml
            ├── kotlin/.../MainActivity.kt
            ├── kotlin/.../util/Platform.android.kt
            └── res/values/           主题与颜色资源
```

### 分层约定

- `model` / `domain` / `data` 是**平台无关**的纯 Kotlin，可单独测试，也可直接复用到其它 target
- `ui` 只做状态到界面的映射；派生数据（可见列表、分组、分面计数）一律即时计算，避免缓存不一致
- 平台能力一律走 `expect` / `actual`（当前只有 `nowMillis()` 一处），`commonMain` 中不出现 `java.*`
- 唯一依赖 Android 的代码是 `androidMain` 下的 `MainActivity`（返回键处理）与 `res/` 资源

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
- 相同的程序化缩略图绘制思路
- 相同的页面与弹层结构、相同的界面文案

原型用于快速确认交互与视觉，本工程用于产出真实安装包。

### 两条实现路径的回归测试

同一组业务语义在两处各有一份断言，用来锁住「HTML 原型」与「Compose 工程」
不会各自漂移：

```bash
# 原型：83 项断言（最小 DOM 桩加载原型脚本）
node prototype/regression-test.js

# 工程：业务层单元测试（commonTest，不依赖 Android 运行时）
cd ScoreAppCompose
./gradlew :composeApp:testDebugUnitTest      # 报告：composeApp/build/reports/tests/
```

覆盖范围：搜索匹配、筛选并集/交集、分面计数（同维度不归零 / 跨维度约束）、
分组排序、姓名切分与 A-Z 索引、头像配色、谱单种子解析、保存时的字段归一化、
详情页元信息行拼接。
