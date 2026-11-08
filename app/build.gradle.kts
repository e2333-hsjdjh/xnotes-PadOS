import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Shared debug/release signing is read from gitignored keystore.properties when present.
// F-Droid builds without that file, producing an unsigned APK it verifies against
// the published (signed) binary for reproducible builds.
val keystorePropertiesFile = rootProject.file("keystore.properties")
// Explicit opt-in for the existing locally installed preview upgrade chain.
val previewUpgradeSigning = providers.gradleProperty("movinkpadPreviewSigning").orNull == "true"
val splitPreview = providers.gradleProperty("splitPreview").orNull == "true"
val aiPreview = providers.gradleProperty("aiPreview").orNull == "true"
require(!(aiPreview && splitPreview)) { "Choose aiPreview or splitPreview, not both" }
val hasReleaseSigning = keystorePropertiesFile.exists()
val keystoreProperties = Properties().apply {
    if (hasReleaseSigning) keystorePropertiesFile.inputStream().use { load(it) }
}

android {
    namespace = "com.xnotes"
    sourceSets {
        getByName("test").java.srcDir("src/sharedTest/java")
        getByName("androidTest").java.srcDir("src/sharedTest/java")
    }
    compileSdk = 36
    // Pinned toolchain for the vendored native builds (F-Droid reproducibility).
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = if (aiPreview) "com.xnotes.movinkpad.aipreview" else if (splitPreview) "com.xnotes.movinkpad.splitpreview" else "com.xnotes.movinkpad"
        manifestPlaceholders["appLabel"] = if (aiPreview) "XNotes AI 测试版" else if (splitPreview) "XNotes 双窗格测试版" else "@string/app_name"
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "com.xnotes.pen.PenTestRunner"
        versionCode = if (aiPreview) 3 else if (splitPreview) 79 else 98
        versionName = "0.9.8"
        ndk {
            // This preview targets the ARM64 MovinkPad and ARM64 test emulator.
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            // The tree-sitter parser tables gzip ~6x: compressed packaging keeps the
            // APK at ~12MB instead of ~30MB, at the cost of extract-on-install.
            useLegacyPackaging = true
        }
    }

    // F-Droid rejects the AGP dependency-metadata block in the APK signing block.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
            getByName("debug").initWith(getByName("release"))
        }
    }

    buildTypes {
        debug {
            versionNameSuffix = if (aiPreview) "-ai-preview3" else if (splitPreview) "-split-preview11" else "-preview1"
        }
        release {
            isDebuggable = false
            signingConfig = when {
                previewUpgradeSigning -> signingConfigs.getByName("debug")
                hasReleaseSigning -> signingConfigs.getByName("release")
                else -> null
            }
            // R8 shrinks/optimises release builds (see proguard-rules.pro for the few keeps). R8
            // output is reproducible (pinned AGP fixes the R8 version + mapping); the one
            // non-reproducible artefact, the baseline profile, is dropped below (see ArtProfile).
            isMinifyEnabled = true
            isShrinkResources = true
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
    }

    // Lists every values-* translation in the manifest so Android 13+ offers a per-app language.
    androidResources {
        generateLocaleConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// F-Droid runs verified builds: its from-source APK must byte-match the released one. AGP's
// auto-merged baseline profile (assets/dexopt/baseline.prof, built from the Compose/AndroidX
// library profiles) is not reproducible across build hosts, so omit it from release builds.
// Tradeoff: slightly slower first-run startup (no ART pre-warm profile).
tasks.configureEach {
    if (name.contains("ArtProfile")) enabled = false
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.pdfbox.android)
    implementation(libs.androidsvg)
    implementation(libs.latex.base)
    implementation(libs.latex.parser)
    implementation(libs.latex.renderer)
    implementation(libs.bundles.commonmark)
    debugImplementation(libs.androidx.ui.tooling)

    androidTestImplementation(libs.junit)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.json)
}

// Robolectric resolves its Android runtime separately from Gradle dependencies.
tasks.withType<Test>().configureEach {
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    // Robolectric's SharedMemory close path accesses JDK FileDescriptor internals on JDK 21.
    // Limit this module opening to the host-test JVM; it does not affect Android builds.
    jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
}
