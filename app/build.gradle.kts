import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// versionCode = git 提交数（git 不可用如直接解压源码时兜底 1）。
// 用 providers.exec 而非配置期裸 exec：配置缓存（org.gradle.configuration-cache=true）
// 复用时它按 value source 重新求值，versionCode 不会冻结在缓存创建时的提交数。
val gitCommitCount: Int = runCatching {
    providers.exec {
        commandLine("git", "rev-list", "HEAD", "--count")
        workingDir = rootDir
    }.standardOutput.asText.get().trim().toInt()
}.getOrDefault(1)

// 签名密码自 local.properties（gitignored）读入：CCHES_STORE_PWD / CCHES_KEY_PWD。
// 2026-10-28 出库改造：密码不再写死在构建脚本、密钥文件不再随 git 分发。
val keystoreProps = Properties()
rootProject.file("local.properties").takeIf { it.exists() }
    ?.inputStream()?.use { keystoreProps.load(it) }

android {
    namespace = "com.xqdk.chess"
    compileSdk = 34
    ndkVersion = libs.versions.ndk.get()

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/cpp/Android.mk")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.xqdk.chess"
        minSdk = 26
        targetSdk = 33
        // versionCode 地板 43（2026-10-29 稳定版压平拍板）：公开仓压成「已有 23 笔 +
        // 单笔稳定版」后 git 提交数回落到 24，而本批真机回归包是 vc43——不加地板
        // 会让装过 vc43 的设备判版本降级、装不上（参照内阁 coerceAtLeast 做法）。
        // 提交数超过 43 后本行自动失效，只增不减。
        versionCode = gitCommitCount.coerceAtLeast(43)
        versionName = "3.1"
        // 构建日期（yyyyMMdd）：检查更新的版本比较依据——公开仓"稳定版=一笔提交"
        // 会强推压平，versionCode（=提交数）随压平回落、前后不可比，与 cabinet
        // 同款用"tag 日期 vs 构建日期"恒单调比较。CI 为 UTC 时区，与用户本地
        // 打 tag 的日期可能差一天，仅影响发版当天自查的边界场景，无实害
        buildConfigField(
            "String", "BUILD_DATE",
            "\"${SimpleDateFormat("yyyyMMdd").format(Date())}\""
        )

        ndk {
            // 只发 arm64-v8a（真机交付唯一目标；2026-10-28 用户拍板"删 ABI 减体积"，
            // x86_64 的 onnxruntime+tflite 约 22MB/包纯为模拟器联调存在）。模拟器联调时
            // 临时加回 "x86_64" 即可（注意：x86_64 模拟器无 arm64 引擎可执行文件，分析引擎必失败）
            abiFilters += listOf("arm64-v8a")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    packaging {
        resources.excludes.addAll(
            listOf("/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md")
        )
        jniLibs {
            useLegacyPackaging = true
        }
    }

    flavorDimensions += "pikafish"
    productFlavors {
        create("armv8") {
            // 基础 NEON 变体（旧名 "armv8-" 的尾横杠曾让任务名与产物名出现双横杠）
            dimension = "pikafish"
            buildConfigField("String", "PIKAFISH_ENGINE_FILE", "\"libpikafish-armv8.so\"")
        }
        create("armv8-dotprod") {
            // dotprod 变体（需 ARMv8.2+：骁龙855/麒麟990/天玑1000+）
            dimension = "pikafish"
            buildConfigField("String", "PIKAFISH_ENGINE_FILE", "\"libpikafish-armv8-dotprod.so\"")
        }
        // 两变体的引擎 so 均随包分发（nativeLibsToJar 全量打包 src/main/pikafish/），
        // 运行时由 PikafishExternalEngine.engineFile() 按 CPU 特性自动选择；
        // 本字段仅作自动选择失败时的回退与产物命名区分
    }

    // https://gist.github.com/mileskrell/7074c10cb3298a2c9d75e733be7061c2
    // Example of declaring Android signing configs using Gradle Kotlin DSL
    signingConfigs {
        create("release") {
            storeFile = file("cchess.release.jks")
            keyAlias = "cchess"
            storePassword = keystoreProps.getProperty("CCHES_STORE_PWD")
            keyPassword = keystoreProps.getProperty("CCHES_KEY_PWD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 密钥文件与密码齐备才用 release 签名（延续既有交付签名，老用户可覆盖安装）；
            // 缺失（CI / 全新克隆）回退 debug 签名保证可构建（对齐内阁 release=debug 签名做法）
            val releaseCfg = signingConfigs["release"]
            signingConfig =
                if (releaseCfg.storeFile?.exists() == true && releaseCfg.storePassword != null) releaseCfg
                else signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        // viewBinding 关闭：全工程 findViewById，从无 Binding 类引用（2026-10-30 死代码清理）
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Log.e 等 android.util 方法在纯 JVM 单测返回默认值而非抛
            // "not mocked"——产品代码的失败路径（如 Board.restoreFromFEN 的
            // Log.e）在 JVM 单测里也要能走到；意外依赖 Android 返回值的测试
            // 会得到 0/null，Robolectric 用例不受影响
            isReturnDefaultValues = true
            all { it.maxHeapSize = "2g" }
        }
    }

    // https://gist.github.com/pankajXdev/574063901ada2fafa329068f41ddb076
    // Config your output file name in Gradle Kotlin DSL
    applicationVariants.all {
        // 用 forEach 遍历输出（outputs.all{} 会解析成 Kotlin 标准库 Iterable.all 谓词，
        // 要求 lambda 返回 Boolean——历史代码里突兀的 `true` 即由此而来）
        outputs.forEach { output ->
            if (output is BaseVariantOutputImpl) {
                val date = SimpleDateFormat("yyyyMMdd").format(Date())
                // 产物名显式用 flavorName-构建类型 拼接（如 armv8-dotprod-release），
                // 不依赖 AGP 变体名——flavor 去尾横杠后变体名会变驼峰拼接（armv8-dotprodRelease）
                val filename = "XQDK_${date}_${versionCode}_${flavorName}-${buildType.name}.apk"
                output.outputFileName = filename
            }
        }
    }
}

// https://withme.skullzbones.com/blog/programming/execute-native-binaries-android-q-no-root/
tasks.register<Jar>("nativeLibsToJar") {
    description = "create a jar archive of the native libs"
    destinationDirectory.set(layout.buildDirectory.dir("native-libs"))
    archiveBaseName.set("native-libs")
    from(fileTree("src/main/pikafish/") {
        // 只打 arm64 引擎文件（abiFilters 同口径）：此前的 **/* 会把其他 ABI
        // 的占位 txt 一并打进 APK，属死重（2026-10-30 死代码清理）
        include("arm64-v8a/*")
    })
    into("lib/")
}

tasks.named("preBuild") {
    dependsOn(tasks.named("nativeLibsToJar"))
}

dependencies {
    // https://withme.skullzbones.com/blog/programming/execute-native-binaries-android-q-no-root/
    implementation(files("$buildDir/native-libs/native-libs.jar"))
    // YOLOv5 棋子检测推理（fp16 模型在 assets/yolov5s_xq_fp16.tflite，回退引擎）
    implementation(libs.tensorflow.lite)
    // ONNX 两步式识别推理（RTMPose 角点 + Swin 分类，主力引擎；模型见 assets/models/README.md）
    implementation(libs.onnxruntime.android)
    implementation(libs.sqliteassethelper)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    // androidTest 源集不存在（无 androidTest 目录），androidx.junit / espresso / junit-jupiter
    // 三个 androidTestImplementation 死依赖已删；testInstrumentationRunner 保留为模板占位
}
