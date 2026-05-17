plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val crocMobileDir = rootProject.layout.projectDirectory.dir("croc-mobile")
val crocAarOutput = layout.projectDirectory.file("libs/croc.aar").asFile
val fdroidGoExecutable = rootProject.layout.projectDirectory.file(".fdroid-go/bin/go").asFile
val goExecutable = providers.gradleProperty("crocGoExecutable")
    .orElse(providers.environmentVariable("CROC_GO"))
    .orElse(if (fdroidGoExecutable.exists()) fdroidGoExecutable.absolutePath else "go")

val buildCrocAar by tasks.registering(Exec::class) {
    group = "build"
    description = "Build croc AAR using gomobile bind."
    workingDir = crocMobileDir.asFile

    inputs.files(
        fileTree(crocMobileDir) {
            exclude("vendor/**")
        },
        fileTree(rootProject.layout.projectDirectory.dir("third_party/croc-src")) {
            exclude(".git/**")
            exclude("build/**")
        }
    )
    outputs.file(crocAarOutput)

    doFirst {
        crocAarOutput.parentFile.mkdirs()
    }

    environment("GOENV", "off")
    environment("GOWORK", "off")
    environment("GOTELEMETRY", "off")

    commandLine(
        "gomobile", "bind",
        "-target=android/arm64,android/arm,android/x86_64",
        "-androidapi=26",
        "-trimpath",
        "-ldflags=-s -w -buildid=",
        "-o", crocAarOutput.absolutePath,
        "."
    )

    outputs.upToDateWhen { false }

    doLast {
        check(crocAarOutput.exists() && crocAarOutput.length() > 0) {
            "croc.aar was not created by gomobile bind!"
        }
        logger.lifecycle("Built croc.aar (${crocAarOutput.length()} bytes) via gomobile")
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
            keepDebugSymbols += "**/libgojni.so"
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(buildCrocAar)
}

dependencies {
    implementation(files(crocAarOutput.absolutePath))

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
