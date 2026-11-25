import com.android.build.api.artifact.ArtifactTransformationRequest
import com.android.build.api.artifact.SingleArtifact
import com.pais.handy.packaging.ApkDownloads
import java.security.KeyStore
import java.security.MessageDigest
import java.util.HexFormat

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val handyVersionName: String =
    rootProject.file("../package.json")
        .takeIf { it.isFile }
        ?.readText()
        ?.let { Regex(""""version"\s*:\s*"([^"]+)"""").find(it)?.groupValues?.get(1) }
        ?: "0.10.4-seionwon"

val handyVersionCode: Int =
    Regex("""^(\d+)\.(\d+)\.(\d+)""")
        .find(handyVersionName)
        ?.let { m ->
            m.groupValues[1].toInt() * 10000 +
                m.groupValues[2].toInt() * 100 +
                m.groupValues[3].toInt()
        }
        ?: 1004

val handyApkFileName = "Handy-v$handyVersionName.apk"
val includeDictationButton = providers.gradleProperty("handyDictationButton").orNull == "true"
val releaseCertificatePin = rootProject.layout.projectDirectory.file("release-signing.sha256")

android {
    namespace = "com.pais.handy"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    // Direct downloads use the original Android permission set.
    if (includeDictationButton) {
        sourceSets.getByName("main").apply {
            kotlin.directories.add("src/dictationButton/kotlin")
            res.directories.add("src/dictationButton/res")
        }
        sourceSets.getByName("test").kotlin.directories.add("src/dictationButtonTest/kotlin")
        buildTypes.all {
            sourceSets.getByName(name).manifest.srcFile("src/dictationButton/AndroidManifest.xml")
        }
    } else {
        sourceSets.getByName("main").kotlin.directories.add("src/standard/kotlin")
    }

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

    val signingEnv = listOf("KEYSTORE", "STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
        .associateWith { providers.environmentVariable("HANDY_ANDROID_$it").orNull }
    val useSigningEnv = signingEnv.values.any { it != null }
    val checkout = rootProject.projectDir.parentFile
    val gitEntry = checkout.resolve(".git")
    val gitCommonDir = if (!useSigningEnv && gitEntry.isFile) {
        val path = providers.exec {
            workingDir(checkout)
            commandLine("git", "rev-parse", "--git-common-dir")
        }.standardOutput.asText.get().trim()
        checkout.resolve(path)
    } else gitEntry
    val localSigning = gitCommonDir.resolve("handy-android-signing")
    val releaseStore = if (useSigningEnv) signingEnv["KEYSTORE"]?.let { file(it) }
        else localSigning.resolve("release.p12").takeIf { it.isFile }
    val releaseSigner = releaseStore?.let { keystore ->
        signingConfigs.create("handyRelease") {
            storeFile = keystore
            if (useSigningEnv) {
                storePassword = signingEnv["STORE_PASSWORD"]
                keyAlias = signingEnv["KEY_ALIAS"]
                keyPassword = signingEnv["KEY_PASSWORD"]
            } else {
                storeType = "PKCS12"
                keyAlias = "handy-release"
                val passwordFile = localSigning.resolve("password")
                storePassword = passwordFile.takeIf { it.isFile }?.readText()?.trimEnd('\r', '\n')
                keyPassword = storePassword
            }
        }
    }

    buildTypes {
        release {
            signingConfig = releaseSigner
            isDebuggable = false
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

val releaseSigning = android.signingConfigs.findByName("handyRelease")
tasks.matching { it.name == "packageRelease" || it.name == "signReleaseBundle" }.configureEach {
    inputs.file(releaseCertificatePin).withPathSensitivity(PathSensitivity.NONE)
    doFirst {
        val store = releaseSigning?.storeFile
        val password = releaseSigning?.storePassword
        val alias = releaseSigning?.keyAlias
        check(store != null && store.isFile && !password.isNullOrEmpty() &&
            !alias.isNullOrEmpty() && !releaseSigning?.keyPassword.isNullOrEmpty()) {
            "Restore the Handy release keystore and its credentials or configure HANDY_ANDROID_* to build a release."
        }
        val keys = KeyStore.getInstance(store, password.toCharArray())
        check(keys.isKeyEntry(alias)) { "The configured alias has no release signing key." }
        val digest = MessageDigest.getInstance("SHA-256").digest(keys.getCertificate(alias).encoded)
        check(HexFormat.of().formatHex(digest) == releaseCertificatePin.asFile.readText().trim()) {
            "The keystore does not match the Handy release signing certificate."
        }
    }
}

// AGP keeps a complete package input for each variant. Only the selected
// installer has an .apk extension, and AGP's final metadata points to it.
abstract class PublishHandyApk : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val packages: DirectoryProperty
    @get:OutputDirectory abstract val listing: DirectoryProperty
    @get:OutputFile abstract val installer: RegularFileProperty
    @get:Internal abstract val transform: Property<ArtifactTransformationRequest<PublishHandyApk>>

    @TaskAction fun publish() {
        val target = installer.get().asFile
        var source: File? = null
        transform.get().submit(this) { artifact ->
            val input = File(artifact.outputFile)
            check(source == null && input.name == "${target.name}.part") { "Expected one universal Handy APK" }
            source = input
            target
        }
        val input = checkNotNull(source) { "Missing Handy package input" }
        // Keep a reference to the signed input for a release publication retry.
        val directory = listing.get().asFile.apply { mkdirs() }
        directory.resolve("package-input.txt").writeText(input.relativeTo(directory).invariantSeparatorsPath)
        ApkDownloads.publish(input.toPath(), target.toPath())
    }
}

androidComponents {
    onVariants { variant ->
        val name = if (variant.buildType == "release") handyApkFileName else "Handy-v$handyVersionName-${variant.name}.apk"
        variant.outputs.forEach { it.outputFileName.set("$name.part") }
        val suffix = variant.name.replaceFirstChar { it.uppercaseChar() }
        val mergedManifest = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
        tasks.withType<Test>().matching { it.name == "test${suffix}UnitTest" }.configureEach {
            inputs.file(mergedManifest)
            systemProperty("handy.test.manifest", mergedManifest.get().asFile.absolutePath)
            systemProperty("handy.test.dictationButton", includeDictationButton)
        }
        val target = rootProject.layout.projectDirectory.file("../$name").asFile.canonicalFile
        val publish = tasks.register<PublishHandyApk>("publish${suffix}HandyApk") {
            installer.set(target)
            outputs.upToDateWhen { ApkDownloads.otherDownloads(target.parentFile.toPath(), target.toPath()).isEmpty() }
            if (variant.buildType == "release") mustRunAfter("publishDebugHandyApk")
        }
        val request = variant.artifacts.use(publish)
            .wiredWithDirectories(PublishHandyApk::packages, PublishHandyApk::listing)
            .toTransformMany(SingleArtifact.APK)
        publish.configure { transform.set(request) }
        tasks.matching { it.name == "package$suffix" }.configureEach { finalizedBy(publish) }
        tasks.matching { it.name == "assemble$suffix" }.configureEach { dependsOn(publish) }
    }
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
    testImplementation("io.mockk:mockk:1.14.5") {
        exclude(group = "org.junit.jupiter") // This module uses JUnit 4.
    }
    testImplementation("org.json:json:20250517")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
