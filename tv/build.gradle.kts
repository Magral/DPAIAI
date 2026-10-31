plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val releaseStore = providers.environmentVariable("ANDROID_KEYSTORE_PATH")
val releasePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD")
val releaseReady = releaseStore.isPresent && releasePassword.isPresent
val sharedSources = layout.buildDirectory.dir("generated/shared")
val copyShared by tasks.registering(Sync::class) {
    from("../app/src/main/java") {
        include("io/github/dovecoteescapee/byedpi/core/ByeDpiProxy.kt")
        include("io/github/dovecoteescapee/byedpi/core/ByeDpiProxyPreferences.kt")
        include("io/github/dovecoteescapee/byedpi/core/TProxyService.kt")
        include("io/github/dovecoteescapee/byedpi/utility/ArgumentsUtils.kt")
        include("io/github/dovecoteescapee/byedpi/utility/TargetProbe.kt")
    }
    into(sharedSources)
}
android {
    namespace = "io.github.magral.dpipilot.tv"
    compileSdk = 34
    ndkVersion = "26.1.10909125"
    defaultConfig {
        applicationId = "io.github.magral.dpipilot.tv"
        minSdk = 21
        targetSdk = 34
        versionCode = 8
        versionName = "0.5.0"
        ndk { abiFilters.addAll(listOf("armeabi-v7a","arm64-v8a","x86","x86_64")) }
    }
    buildFeatures { buildConfig = true }
    sourceSets.getByName("main") {
        java.srcDir(sharedSources)
        jniLibs.srcDir("../app/src/main/jniLibs")
    }
    signingConfigs {
        if (releaseReady) create("permanentRelease") {
            storeFile = file(releaseStore.get())
            storePassword = releasePassword.get()
            keyAlias = "dpipilot"
            keyPassword = releasePassword.get()
            storeType = "PKCS12"
            enableV1Signing = true
            enableV2Signing = true
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseReady) signingConfig = signingConfigs.getByName("permanentRelease")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    dependenciesInfo { includeInApk = false; includeInBundle = false }
}
tasks.named("preBuild") { dependsOn(copyShared,":app:runNdkBuild") }
tasks.configureEach {
    if (name == "validateSigningRelease" || name == "packageRelease") doFirst {
        check(releaseReady) { "Permanent release signing is required" }
    }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
