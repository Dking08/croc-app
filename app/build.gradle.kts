plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val crocSourceDir = rootProject.layout.projectDirectory.dir("third_party/croc-src")
val crocJniLibsRootDir = layout.buildDirectory.dir("generated/jniLibs/croc")
val crocOutputDir = crocJniLibsRootDir.map { it.dir("arm64-v8a") }
val crocOutputFile = crocOutputDir.map { it.file("libcroc.so") }
val goTelemetryDir = rootProject.layout.buildDirectory.dir("go/telemetry")
val goCacheDir = rootProject.layout.buildDirectory.dir("go/cache")
val goModCacheDir = rootProject.layout.buildDirectory.dir("go/mod-cache")
val fdroidGoExecutable = rootProject.layout.projectDirectory.file(".fdroid-go/bin/go").asFile
val goExecutable = providers.gradleProperty("crocGoExecutable")
    .orElse(providers.environmentVariable("CROC_GO"))
    .orElse(if (fdroidGoExecutable.exists()) fdroidGoExecutable.absolutePath else "go")

val buildCrocAndroidArm64 by tasks.registering(Exec::class) {
    group = "build"
    description = "Build croc v10.4.2 for Android arm64 from vendored source."
    workingDir = crocSourceDir.asFile

    inputs.files(
        fileTree(crocSourceDir) {
            exclude(".git/**")
            exclude("build/**")
        }
    )
    outputs.file(crocOutputFile)

    doFirst {
        val vendorDir = crocSourceDir.dir("vendor").asFile
        check(vendorDir.exists()) {
            "Vendored croc dependencies are missing at ${vendorDir.absolutePath}. Run `go mod vendor` in third_party/croc-src."
        }

        crocOutputDir.get().asFile.mkdirs()
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
    environment("GOARM64", "v8.0")
    environment("CGO_ENABLED", "0")

    commandLine(
        goExecutable.get(),
        "build",
        "-mod=vendor",
        "-trimpath",
        "-buildvcs=false",
        "-ldflags=-s -w -buildid=",
        "-o",
        crocOutputFile.get().asFile.absolutePath,
        "."
    )
}

tasks.whenTaskAdded {
    if (name.contains("ArtProfile")) {
        enabled = false
    }
}

android {
    namespace = "com.dking.crocapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dking.crocapp"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "3.2.0"

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
    sourceSets {
        getByName("main") {
            jniLibs.srcDir(crocJniLibsRootDir)
        }
    }
    packaging {
        jniLibs {
            keepDebugSymbols += "**/libcroc.so"
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(buildCrocAndroidArm64)
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
