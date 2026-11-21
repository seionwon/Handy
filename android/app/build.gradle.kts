plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val handyVersionName: String =
    rootProject.file("../package.json")
        .takeIf { it.isFile }
        ?.readText()
        ?.let { Regex(""""version"\s*:\s*"([^"]+)"""").find(it)?.groupValues?.get(1) }
        ?: "0.10.2-seionwon"

val handyVersionCode: Int =
    Regex("""^(\d+)\.(\d+)\.(\d+)""")
        .find(handyVersionName)
        ?.let { m ->
            m.groupValues[1].toInt() * 10000 +
                m.groupValues[2].toInt() * 100 +
                m.groupValues[3].toInt()
        }
        ?: 1002

val handyApkFileName = "Handy-v$handyVersionName.apk"

android {
    namespace = "com.pais.handy"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.pais.handy"
        minSdk = 26
        targetSdk = 36
        versionCode = handyVersionCode
        versionName = handyVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DGGML_OPENMP=OFF",
                    "-DGGML_BUILD_TESTS=OFF",
                    "-DGGML_BUILD_EXAMPLES=OFF",
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_LLAMAFILE=OFF",
                    "-DGGML_CPU_KLEIDIAI=OFF",
                    "-DBUILD_SHARED_LIBS=OFF",
                )
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// FocusTempo와 동일하게 Android Studio/CodeMemo가 보여 주는 기본 디버그 산출물도 버전 포함 앱 이름으로 생성한다.
androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set(handyApkFileName)
        }
    }
}

// 프로젝트 루트에만 버전 포함 APK 1개만 유지해 CodeMemo 실행 탭에 중복 APK가 뜨지 않도록 한다.
val syncRootDebugApk = tasks.register("syncRootDebugApk") {
    val debugApkDir = layout.buildDirectory.dir("outputs/apk/debug")
    val repoRoot = rootProject.projectDir.parentFile
    val apkName = handyApkFileName
    doLast {
        val srcApk = debugApkDir.get().file(apkName).asFile
        if (srcApk.isFile && repoRoot != null && repoRoot.isDirectory) {
            repoRoot.listFiles { file ->
                file.isFile &&
                    file.name.startsWith("Handy-") &&
                    file.name.endsWith(".apk") &&
                    file.name != apkName
            }?.forEach { it.delete() }
            srcApk.copyTo(File(repoRoot, apkName), overwrite = true)
            srcApk.delete()
        }
    }
}

tasks.matching { it.name == "assembleDebug" }.configureEach {
    finalizedBy(syncRootDebugApk)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.01.01"))
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
