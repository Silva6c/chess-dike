import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import java.text.SimpleDateFormat
import java.util.Date

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
        versionCode = gitCommitCount
        versionName = "2.9"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
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
    }

    // https://gist.github.com/mileskrell/7074c10cb3298a2c9d75e733be7061c2
    // Example of declaring Android signing configs using Gradle Kotlin DSL
    signingConfigs {
        create("release") {
            storeFile = file("cchess.release.jks")
            keyAlias = "cchess"
            storePassword = "cchess-store-pwd"
            keyPassword = "cchess-key-pwd"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs["release"]
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
        viewBinding = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
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
        include("**/*")
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
    // https://mvnrepository.com/artifact/com.igormaznitsa/jbbp
    implementation(libs.jbbp)
    // https://github.com/PhilJay/MPAndroidChart
    implementation(libs.mpandroidchart)
    implementation(project(":filepicker"))
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
