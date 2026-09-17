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

跑一遍业务逻辑回归测试（102 项断言）：

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

跑业务层单元测试（50 项断言，`commonTest`，不依赖 Android 运行时）：

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
│   └── regression-test.js          #   102 项业务逻辑断言
│
└── ScoreAppCompose/                # 交付物 2：Compose Multiplatform 工程
    ├── composeApp/src/
    │   ├── commonMain/             #   平台无关：模型 / 领域逻辑 / 数据 / 全部 UI
    │   ├── androidMain/            #   平台实现：Activity / Manifest / 资源
    │   └── commonTest/             #   50 项业务层断言
    ├── gradle/                     #   wrapper 与版本目录
    ├── build.sh / build.bat        #   一键打包脚本
    └── README.md                   #   完整工程文档
```

本地分析输入（原始 APK、jadx 反编译产物、分析工具）已在 `.gitignore` 中排除。

---

## 功能范围

- **乐谱库**：列表 / 网格切换、搜索、按标题·作曲家·曲目类型·乐器·时期·难度·来源筛选、多维分组
- **暂存式筛选**：弹层内的改动先暂存，确认后才落库，并给出「已筛选出 N 首乐谱」反馈
- **详情页**：封面缩略图（程序化绘制）+ 元数据面板（含添加时间）+ 分类归属面板（反查所属谱单）+ 分享主操作
- **导入乐谱**：相册图片合成 / 直接选 PDF，导入后真实写入曲库
- **作曲家**：A–Z 索引分组、姓氏/全名双行展示、作品数徽标，可下钻到作品页
- **我的**：谱单、管理、设置等入口；设置项均可交互（自动识别元数据为可切换开关）
- **危险操作防护**：删除为两段式确认，3 秒无操作自动复原

## 技术栈

| 层 | 选型 |
| --- | --- |
| 原型 | 原生 HTML / CSS / JS，零依赖，Canvas 2D 绘制缩略图 |
| 工程 | Kotlin 2.1.21 + Compose Multiplatform 1.8.2 |
| 构建 | Gradle 8.10.2 + AGP 8.7.3，compileSdk / targetSdk 35，minSdk 24 |
| 测试 | Node（原型 102 项）+ kotlin-test（工程 50 项） |

---

## 与原应用的差异

这是一份**还原 + 明确标注的扩展**，不是逐像素复刻。主要差异：

- **样例数据被放大**——原应用只预置 1 首乐谱 + 1 个谱单，本仓库补到 42 首 + 5 个谱单用于演示
- **「排序」是扩展功能**——原应用没有排序
- **姓氏别名表被扩展**——否则样例数据中部分作曲家的索引字母会退化成汉字
- **部分能力未实现**——PDF 阅读器、真实文件导入、分享落盘、崩溃日志等

### ⚠️ 演示边界（重要）

本仓库定位为**原型演示**，不是可安装使用的应用。下列能力在 UI 上**有入口但不产生真实副作用**，
仅以提示文案反馈，请勿当作已实现功能验收：

| 入口 | 实际行为 |
| --- | --- |
| 分享 | 只提示文件名（或「已分享曲谱信息」），不调起系统分享 |
| 打开乐谱 / 打开 PDF | 只提示文件名，**不打开任何阅读器** |
| 相册选图 / 选 PDF 导入 | 只在曲库里**造一条记录**，不读取真实文件、不落盘 |
| 「我的 → 导入与存储」 | 提示的是**固定演示数值**（128 MB），非真实占用 |
| 「我的 → 清理缓存」 | 只提示「已清理 24 MB」，未做任何清理 |
| 崩溃提示弹层 | 硬编码的演示文案，非真实崩溃日志 |

与之相对，以下能力是**真实生效**的：筛选 / 搜索 / 分组 / 排序、
编辑与删除（含二次确认）、谱单归属反查、导入记录落库与持久化、
「我的」页元数据统计行的跳转筛选、封面缩略图版式参数渲染。

完整清单（含每处改动的理由）见 [`ScoreAppCompose/README.md`](ScoreAppCompose/README.md) 的
「与原应用的差异清单」一节。

---

## 许可

本仓库的**重写代码**可自由参考。原应用的名称、界面设计与资源版权归其各自权利人所有，
本仓库不包含这些材料。
