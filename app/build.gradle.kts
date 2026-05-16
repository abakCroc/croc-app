plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val crocSourceDir = rootProject.layout.projectDirectory.dir("third_party/croc-src")
val crocOutputSo = layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libcroc.so").asFile
val crocOutputSoArm = layout.projectDirectory.file("src/main/jniLibs/armeabi-v7a/libcroc.so").asFile
val crocOutputSoX86 = layout.projectDirectory.file("src/main/jniLibs/x86_64/libcroc.so").asFile
val goTelemetryDir = rootProject.layout.buildDirectory.dir("go/telemetry")
val goCacheDir = rootProject.layout.buildDirectory.dir("go/cache")
val goModCacheDir = rootProject.layout.buildDirectory.dir("go/mod-cache")
val fdroidGoExecutable = rootProject.layout.projectDirectory.file(".fdroid-go/bin/go").asFile
val goExecutable = providers.gradleProperty("crocGoExecutable")
    .orElse(providers.environmentVariable("CROC_GO"))
    .orElse(if (fdroidGoExecutable.exists()) fdroidGoExecutable.absolutePath else "go")

val buildCrocAndroidArm64 by tasks.registering(Exec::class) {
    group = "build"
    description = "Build croc as shared library for Android arm64."
    workingDir = crocSourceDir.asFile

    inputs.files(
        fileTree(crocSourceDir) {
            exclude(".git/**")
            exclude("build/**")
        }
    )
    outputs.file(crocOutputSo)

    doFirst {
        val vendorDir = crocSourceDir.dir("vendor").asFile
        check(vendorDir.exists()) {
            "Vendored croc dependencies are missing at ${vendorDir.absolutePath}. Run `go mod vendor` in third_party/croc-src."
        }

        crocOutputSo.parentFile.mkdirs()
        goTelemetryDir.get().asFile.mkdirs()
        goCacheDir.get().asFile.mkdirs()
        goModCacheDir.get().asFile.mkdirs()
    }

    environment("GOENV", "off")
    environment("GOWORK", "off")
    environment("GOTELEMETRY", "off")
    environment("GOTELEMETRYDIR", goTelemetryDir.get().asFile.absolutePath)
    environment("GOCACHE", goCacheDir.get().asFile.absolutePath)
    environment("GOMODCACHE", goModCacheDir.get().asFile.absolutePath)
    environment("GOOS", "android")
    environment("GOARCH", "arm64")
    environment("CGO_ENABLED", "1")

    commandLine(
        goExecutable.get(),
        "build",
        "-mod=vendor",
        "-tags=capi",
        "-buildmode=c-shared",
        "-trimpath",
        "-buildvcs=false",
        "-ldflags=-s -w -buildid=",
        "-o",
        crocOutputSo.absolutePath,
        "./cmd/capi"
    )

    outputs.upToDateWhen { false }

    doLast {
        check(crocOutputSo.exists() && crocOutputSo.length() > 0) {
            "libcroc.so was not created by Go build! Expected at ${crocOutputSo.absolutePath}"
        }
        logger.lifecycle("Built libcroc.so (${crocOutputSo.length()} bytes) at ${crocOutputSo.absolutePath}")
    }
}

val buildCrocAndroidArm by tasks.registering(Exec::class) {
    group = "build"
    description = "Build croc as shared library for Android armeabi-v7a."
    workingDir = crocSourceDir.asFile

    inputs.files(
        fileTree(crocSourceDir) {
            exclude(".git/**")
            exclude("build/**")
        }
    )
    outputs.file(crocOutputSoArm)

    doFirst {
        val vendorDir = crocSourceDir.dir("vendor").asFile
        check(vendorDir.exists()) {
            "Vendored croc dependencies are missing at ${vendorDir.absolutePath}. Run `go mod vendor` in third_party/croc-src."
        }

        crocOutputSoArm.parentFile.mkdirs()
    }

    environment("GOENV", "off")
    environment("GOWORK", "off")
    environment("GOTELEMETRY", "off")
    environment("GOTELEMETRYDIR", goTelemetryDir.get().asFile.absolutePath)
    environment("GOCACHE", goCacheDir.get().asFile.absolutePath)
    environment("GOMODCACHE", goModCacheDir.get().asFile.absolutePath)
    environment("GOOS", "android")
    environment("GOARCH", "arm")
    environment("GOARM", "7")
    environment("CGO_ENABLED", "1")

    commandLine(
        goExecutable.get(),
        "build",
        "-mod=vendor",
        "-tags=capi",
        "-buildmode=c-shared",
        "-trimpath",
        "-buildvcs=false",
        "-ldflags=-s -w -buildid=",
        "-o",
        crocOutputSoArm.absolutePath,
        "./cmd/capi"
    )

    outputs.upToDateWhen { false }

    doLast {
        check(crocOutputSoArm.exists() && crocOutputSoArm.length() > 0) {
            "libcroc.so (armeabi-v7a) was not created by Go build!"
        }
        logger.lifecycle("Built libcroc.so armeabi-v7a (${crocOutputSoArm.length()} bytes)")
    }
}

val buildCrocAndroidX86 by tasks.registering(Exec::class) {
    group = "build"
    description = "Build croc as shared library for Android x86_64."
    workingDir = crocSourceDir.asFile

    inputs.files(
        fileTree(crocSourceDir) {
            exclude(".git/**")
            exclude("build/**")
        }
    )
    outputs.file(crocOutputSoX86)

    doFirst {
        val vendorDir = crocSourceDir.dir("vendor").asFile
        check(vendorDir.exists()) {
            "Vendored croc dependencies are missing at ${vendorDir.absolutePath}. Run `go mod vendor` in third_party/croc-src."
        }

        crocOutputSoX86.parentFile.mkdirs()
    }

    environment("GOENV", "off")
    environment("GOWORK", "off")
    environment("GOTELEMETRY", "off")
    environment("GOTELEMETRYDIR", goTelemetryDir.get().asFile.absolutePath)
    environment("GOCACHE", goCacheDir.get().asFile.absolutePath)
    environment("GOMODCACHE", goModCacheDir.get().asFile.absolutePath)
    environment("GOOS", "android")
    environment("GOARCH", "amd64")
    environment("CGO_ENABLED", "1")

    commandLine(
        goExecutable.get(),
        "build",
        "-mod=vendor",
        "-tags=capi",
        "-buildmode=c-shared",
        "-trimpath",
        "-buildvcs=false",
        "-ldflags=-s -w -buildid=",
        "-o",
        crocOutputSoX86.absolutePath,
        "./cmd/capi"
    )

    outputs.upToDateWhen { false }

    doLast {
        check(crocOutputSoX86.exists() && crocOutputSoX86.length() > 0) {
            "libcroc.so (x86_64) was not created by Go build! Expected at ${crocOutputSoX86.absolutePath}"
        }
        logger.lifecycle("Built libcroc.so x86_64 (${crocOutputSoX86.length()} bytes) at ${crocOutputSoX86.absolutePath}")
    }
}

afterEvaluate {
    val ndk = android.ndkDirectory.absolutePath
    buildCrocAndroidArm64.configure {
        environment("CC", "$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang")
    }
    buildCrocAndroidX86.configure {
        environment("CC", "$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/x86_64-linux-android26-clang")
    }
    buildCrocAndroidArm.configure {
        environment("CC", "$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/armv7a-linux-androideabi26-clang")
    }
}

tasks.configureEach {
    if (
        name.contains("ArtProfile", ignoreCase = true) ||
        name.contains("BaselineProfile", ignoreCase = true)
    ) {
        enabled = false
    }
}

android {
    namespace = "com.dking.crocapp"
    compileSdk = 35
    ndkVersion = "29.0.14033849"

    defaultConfig {
        applicationId = "com.dking.crocapp"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "4.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/libcroc.so"
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(buildCrocAndroidArm64)
    dependsOn(buildCrocAndroidArm)
    dependsOn(buildCrocAndroidX86)
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")

    // Activity & Lifecycle
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DataStore Preferences
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // CameraX (for QR scanning)
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")

    // ZXing for QR Generation
    implementation("com.google.zxing:core:3.5.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Splash Screen
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
