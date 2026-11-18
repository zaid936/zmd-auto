import org.jetbrains.kotlin.gradle.dsl.KotlinJvmOptions

// ZMD: the Android Auto engine of ZMD Auto as a library, so ZMD Car can carry it inside its own
// APK (no second app to install). It compiles the SAME sources as :app (the standalone ZMD Auto),
// with its own manifest (src/main/AndroidManifest.xml: every component in ZMD Car's ":auto"
// process). Published to build/engine-repo (with a POM, so ZMD Car gets the dependencies too):
//   gradlew :engine:publishReleasePublicationToEngineRepository
// AGPL-3.0 like the rest of this project: an app that ships it must offer its source.
plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("kapt")
    `maven-publish`
}

val engineVersion = "0.3.12-zmd.3"

android {
    compileSdk = 36
    ndkVersion = "29.0.14206865"
    namespace = "com.andrerinas.openheadunit"

    buildFeatures {
        buildConfig = true
        aidl = true // needed for shizuku
    }

    externalNativeBuild {
        cmake {
            path("../app/src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Same locale scan as :app (the settings screen offers these languages).
    val resDir = file("../app/src/main/res")
    val availableLocales = resDir.listFiles { f ->
        f.isDirectory && f.name.startsWith("values-") &&
            !f.name.contains("night") && !f.name.contains("land") && !f.name.contains("port") &&
            !f.name.matches(Regex("values-[whsml]\\d+.*")) && !f.name.matches(Regex("values-v\\d+")) &&
            f.resolve("strings.xml").exists()
    }?.map { it.name.removePrefix("values-") }?.sorted() ?: emptyList()

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("../app/proguard-project.txt")
        // :app gets these from its applicationId/version; a library has to set them. The engine is
        // only ever packaged inside ZMD Car, so APPLICATION_ID is ZMD Car's.
        buildConfigField("String", "APPLICATION_ID", "\"com.zmd.carhub\"")
        buildConfigField("String", "VERSION_NAME", "\"$engineVersion\"")
        buildConfigField("int", "VERSION_CODE", "112003")
        buildConfigField("String", "FLAVOR", "\"github\"")
        buildConfigField("String", "AVAILABLE_LOCALES", "\"${availableLocales.joinToString(",")}\"")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        externalNativeBuild { cmake { cppFlags("") } }
    }

    val copyRootAssets = tasks.register<Copy>("copyRootAssets") {
        from("${rootProject.rootDir}/CHANGELOG.md", "${rootProject.rootDir}/LICENSE")
        into(layout.buildDirectory.dir("generated/assets/root"))
    }
    tasks.withType<com.android.build.gradle.tasks.MergeSourceSetFolders>().configureEach { dependsOn(copyRootAssets) }
    tasks.configureEach { if (name.contains("lint", ignoreCase = true)) dependsOn(copyRootAssets) }

    sourceSets {
        getByName("main") {
            manifest.srcFile("src/main/AndroidManifest.xml")
            java.srcDirs(
                "../app/src/main/java",
                "../app/src/github/java",            // DummyVpnService, VpnControl (github flavor)
                "../contract/src/main/java",         // HeadUnitIntent (no resources of its own used)
            )
            res.srcDirs("../app/src/main/res")
            assets.srcDirs("../app/src/main/assets", layout.buildDirectory.dir("generated/assets/root").get().asFile)
            aidl.srcDirs("../app/src/main/aidl")
            jniLibs.srcDirs("../app/src/main/jniLibs")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        (this as KotlinJvmOptions).jvmTarget = "1.8"
    }

    lint {
        abortOnError = false
        disable += "PackagedPrivateKey"
    }

    publishing {
        singleVariant("release")
    }
}

dependencies {
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("com.google.protobuf:protobuf-java:3.25.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.media:media:1.6.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.startup:startup-runtime:1.1.1")
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    implementation("androidx.lifecycle:lifecycle-extensions:2.2.0")
    kapt("androidx.lifecycle:lifecycle-compiler:2.6.2")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.0")
    implementation("androidx.multidex:multidex:2.0.1")
    implementation("androidx.navigation:navigation-fragment-ktx:2.3.5")
    implementation("androidx.navigation:navigation-ui-ktx:2.3.5")
    implementation("com.linkedin.dexmaker:dexmaker:2.28.3")
    implementation("com.github.bumptech.glide:glide:4.16.0")
    kapt("com.github.bumptech.glide:compiler:4.16.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("com.github.topjohnwu.libsu:core:6.0.0")
}

publishing {
    publications {
        create<MavenPublication>("release") {
            groupId = "com.zmd"
            artifactId = "auto-engine"
            version = engineVersion
            afterEvaluate { from(components["release"]) }
        }
    }
    repositories {
        maven {
            name = "engine"
            url = uri(rootProject.layout.buildDirectory.dir("engine-repo"))
        }
    }
}
