import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    // 本工程仅落地 Android 平台；commonMain 中的 UI 与业务逻辑保持平台无关，
    // 后续如需扩展 iOS / Desktop，只需补充对应 target 与 sourceSet。
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    sourceSets {
        // 统一开启常用实验性 API 的 opt-in，避免在每个 Composable 上重复标注：
        // - Material3 的 ModalBottomSheet / SheetState 系列
        // - foundation 的 FlowRow / FlowColumn
        // - Compose UI 的若干实验性绘图与文本测量接口
        all {
            languageSettings.optIn("androidx.compose.material3.ExperimentalMaterial3Api")
            languageSettings.optIn("androidx.compose.foundation.layout.ExperimentalLayoutApi")
            languageSettings.optIn("androidx.compose.ui.ExperimentalComposeUiApi")
        }

        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
        }
        // 业务逻辑是纯函数，用普通单元测试即可覆盖，不需要设备
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)
        }
    }
}

android {
    namespace = "com.example.scoreapp"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.example.scoreapp"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 25
        versionName = "1.24"
    }

    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets["main"].res.srcDirs("src/androidMain/res")

    // 内置乐谱 PDF 的**构建期注入**。
    //
    // 为什么这么做：`SampleLibrary` 里第一份样例乐谱声明了
    // `assetPdf = "moonlight_op27_no2.pdf"`，而这份 PDF 是从原应用反编译产物里
    // 取出的第三方版权扫描件——它**不能进版本库**（见仓库根 `.gitignore`）。
    // 于是：本地存在就打包进去，不存在就什么都不做，仓库永远干净。
    //
    // 目录走 build/ 下的临时目录，不落进 src/，避免污染工作区、也避免
    // 「明明没提交却出现在 git status 里」。缺失时 APK 里没有 assets/scores/，
    // 此时 PdfAssets.installBundledScores 返回空映射，封面退化为程序化绘制。
    // 注意目录层级：AGP 把 assets 源目录的**根**映射到 APK 的 `assets/`，
    // 所以 srcDir 必须是 `injectedAssets`（其下再放 `scores/` 子目录），
    // APK 里才会出现 `assets/scores/xxx.pdf`。
    // 若把 srcDir 直接指到 `injectedAssets/scores`，文件会落到 `assets/xxx.pdf`，
    // 而 `PdfAssets.installMissing` 读的是 `assets.list("scores")`，就永远找不到。
    val injectedAssetsRoot = layout.buildDirectory.dir("injectedAssets")
    val injectedPdfDir = injectedAssetsRoot.map { it.dir("scores") }
    val localPdfDir = rootProject.file("../decoded_resources/assets/scores")
    val injectBundledPdf by tasks.registering(Copy::class) {
        description = "若本地存在反编译产物的乐谱 PDF，则复制进 APK assets/scores（文件不入 git）"
        onlyIf { localPdfDir.isDirectory }
        from(localPdfDir) { include("*.pdf") }
        into(injectedPdfDir)
    }

    // 只挂进 debug 变体：release 包不该意外带上版权内容
    sourceSets["debug"].assets.srcDir(injectedAssetsRoot)
    tasks.matching { it.name == "mergeDebugAssets" }.configureEach {
        dependsOn(injectBundledPdf)
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}
