# 乐谱管理 · 逆向重写演示

从一份 Android APK 的反编译产物中**只提炼业务规格**，然后**独立重写**出两套可运行的实现：

| 交付物 | 位置 | 形态 |
| --- | --- | --- |
| **交互原型** | [`prototype/scoreapp-prototype.html`](prototype/scoreapp-prototype.html) | 纯 HTML + 原生 JS，单文件，双击即开 |
| **安卓工程** | [`ScoreAppCompose/`](ScoreAppCompose/) | Kotlin Compose Multiplatform，纯命令行打包 |

两套实现共享同一份规格：设计令牌、样例数据、筛选与分组语义、分面计数规则、
页面与弹层结构、界面文案。

---

## 这是什么，不是什么

**是**：一份「怎么在拿不到源码的情况下，把一款 Compose 应用的行为完整还原出来」的实践记录。
反编译产物只用来读取数据结构、算法语义、交互规则、设计令牌与文案。

**不是**：原应用的源码副本。**本仓库不包含任何反编译代码**——
所有实现均按自己的分层与命名独立重写，反编译产物仅作为本地分析输入，未入库。

---

## 快速开始

### 交互原型

无需任何环境，直接用浏览器打开：

```
prototype/scoreapp-prototype.html
```

跑一遍业务逻辑回归测试（200 项断言）：

```bash
node prototype/regression-test.js
```

### 安卓工程

需要 JDK 17 + Android SDK（build-tools 35 / platform 35），**不需要 Android Studio**。

```bash
cd ScoreAppCompose

# Git Bash / macOS / Linux
export JAVA_HOME="<JDK 17 路径>"
export ANDROID_HOME="<Android SDK 路径>"
./gradlew :composeApp:assembleDebug --console=plain
```

Windows 用户也可直接运行 `build.bat`（或 Git Bash 下 `./build.sh`）。

产物：`ScoreAppCompose/composeApp/build/outputs/apk/debug/composeApp-debug.apk`

跑业务层单元测试（67 项断言，`commonTest`，不依赖 Android 运行时）：

```bash
./gradlew :composeApp:testDebugUnitTest
```

> 更详细的构建说明、SDK 路径配置、Gradle 缓存问题，
> 见 [`ScoreAppCompose/README.md`](ScoreAppCompose/README.md)。

---

## 仓库结构

```
.
├── prototype/                      # 交付物 1：HTML 交互原型
│   ├── scoreapp-prototype.html     #   单文件应用（390×844 手机外壳）
│   └── regression-test.js          #   200 项业务逻辑断言
│
├── tools/
│   └── make_proto_pdf.py           #   生成原型内置 PDF 的真实字节码（改封面样例时跑它）
│
└── ScoreAppCompose/                # 交付物 2：Compose Multiplatform 工程
    ├── composeApp/src/
    │   ├── commonMain/             #   平台无关：模型 / 领域逻辑 / 数据 / 全部 UI
    │   ├── androidMain/            #   平台实现：Activity / Manifest / 资源 / 文件·分享·渲染
    │   └── commonTest/             #   67 项业务层断言
    ├── gradle/                     #   wrapper 与版本目录
    ├── build.sh / build.bat        #   一键打包脚本
    └── README.md                   #   完整工程文档
```

> `tools/proto_pdf_b64.txt` 是 `make_proto_pdf.py` 的产物，已内联进
> `scoreapp-prototype.html`；该文件本身只是中间产物，可随时删除重生成。

本地分析输入（原始 APK、jadx 反编译产物、分析工具）已在 `.gitignore` 中排除。

---

## 功能范围

- **乐谱库**：列表 / 网格切换、搜索、按标题·作曲家·曲目类型·乐器·时期·难度·来源筛选、多维分组
- **暂存式筛选**：弹层内的改动先暂存，确认后才落库，并给出「已筛选出 N 首乐谱」反馈
- **详情页**：封面缩略图 + 元数据面板（含添加时间）+ 分类归属面板（反查所属谱单）+ 分享主操作
- **封面渲染**：有实体文件的乐谱取**文件首页**当封面（PDF 走页面渲染，相册图走位图解码）；
  加载失败叠「预览加载失败」提示层；没有文件才退回程序化绘制。三态口径与缓存策略两侧一致
- **导入乐谱**：相册图片合成 / 直接选 PDF，导入后真实写入曲库
- **PDF 阅读器**：翻页、缩放 1–4×、页码徽标，加载 / 失败 / 空文档 / 就绪四态分明
- **分享**：有 PDF 走附件分享，无 PDF 走纯文本曲谱信息
- **作曲家**：A–Z 索引分组、姓氏/全名双行展示、作品数徽标，可下钻到作品页
- **我的**：谱单、管理、设置等入口；设置项均可交互（自动识别元数据为可切换开关）
- **危险操作防护**：删除为两段式确认，3 秒无操作自动复原

## 技术栈

| 层 | 选型 |
| --- | --- |
| 原型 | 原生 HTML / CSS / JS，零依赖，Canvas 2D 绘制缩略图 |
| 工程 | Kotlin 2.1.21 + Compose Multiplatform 1.8.2 |
| 构建 | Gradle 8.10.2 + AGP 8.7.3，compileSdk / targetSdk 35，minSdk 24 |
| 测试 | Node（原型 200 项）+ kotlin-test（工程 67 项） |

---

## 与原应用的差异

这是一份**还原 + 明确标注的扩展**，不是逐像素复刻。主要差异：

- **样例数据被放大**——原应用只预置 1 首乐谱 + 1 个谱单，本仓库补到 42 首 + 5 个谱单用于演示
- **「排序」是扩展功能**——原应用没有排序
- **姓氏别名表被扩展**——否则样例数据中部分作曲家的索引字母会退化成汉字
- **三处原应用瑕疵已改正**——见下文「原应用瑕疵与处置」

### ⚠️ 演示边界（重要）

本仓库定位为**演示原型**，不是可安装使用的应用。

**「文件与系统能力」这一层，两个交付物现在是一致的**：

| 入口 | HTML 原型 | Compose 工程 |
| --- | --- | --- |
| 分享 | 已接通：面板展示真实的分享文件名与正文，按「有 PDF / 无 PDF」走附件或纯文本两条分支 | 已接通：`ShareUtil` 走 `FileProvider` 真实发 `ACTION_SEND` |
| 打开乐谱 | 已接通：进入 PDF 阅读器（翻页 / 缩放 1–4× / 页码徽标 / 加载·空·错误态） | 已接通：`PdfRenderer` 真渲染，`PdfViewerScreen` |
| 相册选图导入 | 已接通：模拟系统多选器 → A4 合成 → 落盘 → 入库 | 已接通：`GetMultipleContents` → `PdfDocument` 真合成 → 落 `files/scores/` |
| 选 PDF 导入 | 已接通：模拟文件选择器 → 拷入 `files/scores/` → 入库 | 已接通：`GetContent` → 真拷贝 → 入库 |
| 封面（文件首页） | 已接通：解析内置 PDF 的真实字节，渲染第 0 页当封面 | 已接通：`CoverRenderer` + `PdfRenderer` 渲首页（内含 12 MB 缓存） |
| 「我的 → 导入与存储」 | 提示的是**固定演示数值**（128 MB） | 同左 |
| 「我的 → 清理缓存」 | 只提示「已清理 24 MB」 | 同左 |
| 崩溃提示弹层 | 硬编码的演示文案 | 同左 |

**两侧的「真实」程度仍然不同，这点必须说清楚：**

- **Compose 侧是真的**：`PdfRenderer` / `PdfDocument` / `FileProvider` 都是 Android 系统能力，
  读写真实文件、发真实分享意图。
- **原型侧是「规格对译 + 最小真实解析」**：浏览器里没有文件系统，也没有 `PdfRenderer`，因此：
  - **算得出真值的部分按原应用规格逐条实现**，并被回归测试锁住 ——
    标题归一（`pdfTitle`）、落盘文件名净化（`copyPdfToLocal`）、A4 合成结果与失败文案
    （`imagesToPdf`）、分享文件名与正文（`ShareUtil`）、页数与「有 / 无 PDF」判据
  - **封面是半真半演示**：内置 PDF 带**真实字节码**
    （由 `tools/make_proto_pdf.py` 生成，结构完整、xref 自洽、可被 pdfium 解析），
    原型自己解 FlateDecode、逐条执行绘图算子、把第 0 页画到 Canvas ——
    这条链路是**真的**，但不是通用 PDF 渲染器，只覆盖该 PDF 用到的那一小组算子。
    **用户导入的 PDF 在原型里没有字节可解，会落到「预览加载失败」**，
    这是刻意的：不假装能渲染读不到的东西。
  - **系统选择器是等效面板**，文件实体用一个内存账本 `FILE_STORE` 记账
    （原应用的真实判据是 `file.exists() && file.length() > 0`）

想逐个状态查看，直接用页面左下角的「演示直达」条，
或用 `#demo=reader` / `#demo=share-pdf` / `#demo=album` 等锚点直达。

### 关于那份内置乐谱 PDF（版权处理）

样例曲库第一条乐谱声明了 `assetPdf = "moonlight_op27_no2.pdf"`，而这份文件是从
原应用反编译产物里取出的**第三方版权扫描件**。处理方式：

- **不进版本库。** `.gitignore` 排除了 `decoded_resources/` 与所有 `*.pdf`。
  新克隆下来的仓库里**没有它**，这是预期状态。
- **Compose 侧构建时从本地注入。** 本地存在 `decoded_resources/assets/scores/*.pdf`
  时，`injectBundledPdf` 任务把它复制进 APK 的 `assets/scores/`（只挂 debug 变体）；
  不存在就什么都不做，仓库永远干净。
- **缺失时优雅降级，不是报错。** 此时内置乐谱没有可用文件，封面退回程序化绘制、
  「打开乐谱」提示还没有 PDF。功能不缺，只是少了预览图。这条降级路径由
  `BundledScoreResolverTest` 覆盖。
- **原型侧不受影响**：HTML 原型内嵌的是由 `tools/make_proto_pdf.py` **自己生成**的
  同构 PDF（结构完整、可被 pdfium 解析），不含任何版权内容。

> 踩过的坑：AGP 把 assets 源目录的**根**映射到 APK 的 `assets/`，所以
> `assets.srcDir` 必须指向含 `scores/` 子目录的那一级，否则文件会落到
> `assets/xxx.pdf`，而运行时读的是 `assets/scores/`，APK 里明明有文件却当没有。

与之相对，以下能力在两侧都是**真实生效**的：筛选 / 搜索 / 分组 / 排序、
编辑与删除（含二次确认）、谱单归属反查、导入记录落库、
「我的」页元数据统计行的跳转筛选、封面缩略图版式参数渲染。

### 原应用瑕疵与处置

反编译证据显示原应用本身有三处不干净。**已全部改正**，并入了回归测试：

| # | 现象 | 处置 |
| --- | --- | --- |
| ① | 导入回调与保存回调两个调用点都把 `未编目`（语义属「曲目类型」）填进了**作曲家**字段，后面每个占位值顺移一格 | 按语义归位：作曲家→`佚名`、类型→`未编目`、乐器→`未分类`、时期→`未指定`、难度→`—`、来源→`本地导入`。写成显式语义表并由测试锁住，不再靠位置对齐 |
| ② | 阅读器顶栏的「共 N 页」副标题与「N 页」徽标**重复** | 副标题只在就绪态报页数，徽标独立保留（维持原视觉）；两者口径一致，不再各说各的 |
| ③ | `pageCount == 0` 时副标题仍是「读取中…」，与正文「这份 PDF 没有任何页面」**并存** | 显式四态：`loading→读取中…`、`failed→无法打开`、`empty→没有页面`、`ready→共 N 页`。空文档不再冒充「读取中」，顶栏与正文不再打架 |

第 ① 处还纠正了一个上游误读：原应用两个导入调用点写的都是 `本地导入`，
曾误加过一个 `相册导入` 取值，那会把同一个来源拆成两个筛选面 —— 已移除。

完整清单（含每处改动的理由）见 [`ScoreAppCompose/README.md`](ScoreAppCompose/README.md) 的
「与原应用的差异清单」一节。

---

## 许可

本仓库的**重写代码**可自由参考。原应用的名称、界面设计与资源版权归其各自权利人所有，
本仓库不包含这些材料。
