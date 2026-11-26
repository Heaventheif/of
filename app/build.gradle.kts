import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun releaseSigningCredential(envVar: String, gradleProp: String): String? {
    System.getenv(envVar)?.takeIf { it.isNotBlank() }?.let { return it }
    providers.gradleProperty(gradleProp).orNull?.takeIf { it.isNotBlank() }?.let { return it }
    val local = rootProject.file("local.properties")
    if (local.isFile) {
        val props = Properties()
        local.inputStream().use { props.load(it) }
        return props.getProperty(gradleProp)?.takeIf { it.isNotBlank() }
    }
    return null
}

val ofmStorePassword: String? = releaseSigningCredential("OFM_STORE_PASSWORD", "ofm.storePassword")
val ofmKeyAlias: String? = releaseSigningCredential("OFM_KEY_ALIAS", "ofm.keyAlias")
val ofmKeyPassword: String? = releaseSigningCredential("OFM_KEY_PASSWORD", "ofm.keyPassword")

android {
    namespace = "org.openfilemanager.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.openfilemanager.app"
        minSdk = 30
        targetSdk = 36
        versionCode = 25
        versionName = "0.8.8"
        // (تحسين) موارد المكتبات بلغات التطبيق فقط: تُحذف ترجمات AndroidX والمواد
        // في عشرات اللغات غير المستعملة من الحزمة
    }

    androidResources {
        // بديل resourceConfigurations المهجور (AGP 8.9+): تصفية اللغات إلى العربية والإنجليزية.
        localeFilters += listOf("ar", "en")
    }

    signingConfigs {
        create("release") {
            val ks = rootProject.file("release.keystore")
            if (ks.exists()) {
                storeFile = ks
                storePassword = ofmStorePassword
                keyAlias = ofmKeyAlias
                keyPassword = ofmKeyPassword ?: ofmStorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (rootProject.file("release.keystore").exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    packaging {
        // ملفات ميتة لا يحتاجها التطبيق وقت التشغيل
        resources.excludes += setOf(
            "META-INF/{AL2.0,LGPL2.1}",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            "META-INF/*.kotlin_module",
            "DebugProbesKt.bin",
            "kotlin-tooling-metadata.json"
        )
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        // (F-18) كل استثناء موثق بسبب محدد؛ راجعه مع كل ترقية AGP، وفضّل
        // suppression المحلي عند نقطة واحدة على تعطيل الفحص للمشروع كله.
        // ScopedStorage: التطبيق مدير ملفات يعتمد MANAGE_EXTERNAL_STORAGE
        //   عمداً (مطلوب من الإعدادات عبر PermissionGate) — التحذير سياسي هنا لا تقني.
        // UsableSpace: File.usableSpace يُستخدم في spaceBoundLimits عمداً
        //   كأفضل تقدير قبل الاستخراج؛ الدقة الأعلى عبر StorageManager ليست مطلوبة.
        // GradleDependency / AndroidGradlePluginVersion: تُلغى حتى لا يفشل lintDebug
        //   مع كل إصدار جديد قبل مراجعة الترقية عمداً (الترقية قرار منفصل موثق).
        // PluralsCandidate: عددٌ قليل من الرسائل يستخدم صيغاً كمية مخصصة
        //   (الصفر/الاثنان بالعربية) داخل strings عمداً؛ المحلل لا يرى النية.
        disable += "ScopedStorage"
        disable += "UsableSpace"
        disable += "GradleDependency"
        disable += "AndroidGradlePluginVersion"
        disable += "PluralsCandidate"
    }
}

val allowUnsignedRelease = providers.gradleProperty("ofmAllowUnsignedRelease").map { it == "true" }.getOrElse(false)
tasks.register("checkReleaseSigning") {
    group = "verification"
    description = "Fails release builds when the original release.keystore or its credentials are missing."
    doLast {
        if (allowUnsignedRelease) {
            logger.warn(
                "WARNING: ofmAllowUnsignedRelease=true - building an UNSIGNED release APK on purpose. " +
                    "It will NOT install over an existing installation; sign it with the original keystore before distribution."
            )
            return@doLast
        }
        val ks = rootProject.file("release.keystore")
        if (!ks.exists()) {
            throw GradleException(
                "release.keystore is MISSING: a release build would be unsigned and Android would refuse to " +
                    "update it over an existing installation (INSTALL_FAILED_UPDATE_INCOMPATIBLE).\n" +
                    "Copy the ORIGINAL keystore from the previous release to the project root - never generate a " +
                    "replacement if users install updates over an existing app.\n" +
                    "If you really want an unsigned release build (e.g. CI on the source archive), pass " +
                    "-PofmAllowUnsignedRelease=true, then sign it with the original keystore."
            )
        }
        val effectiveKeyPassword = ofmKeyPassword ?: ofmStorePassword
        val missing = buildList {
            if (ofmStorePassword == null) add("OFM_STORE_PASSWORD (or gradle property 'ofm.storePassword')")
            if (ofmKeyAlias == null) add("OFM_KEY_ALIAS (or gradle property 'ofm.keyAlias')")
            if (effectiveKeyPassword == null) add("OFM_KEY_PASSWORD (or gradle property 'ofm.keyPassword')")
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Release signing credentials are MISSING: ${missing.joinToString()}.\n" +
                    "Provide them via environment variables, ~/.gradle/gradle.properties or local.properties " +
                    "(ofm.storePassword / ofm.keyAlias / ofm.keyPassword; the key password falls back to the store password).\n" +
                    "Never commit credentials or keystores. Debug builds do not need release credentials."
            )
        }
    }
}
tasks.matching { it.name == "assembleRelease" || it.name == "packageRelease" || it.name == "bundleRelease" }.configureEach {
    dependsOn("checkReleaseSigning")
}

dependencies {
    implementation(project(":core"))
    // (F-07/F-01) اختبارات وحدة لطبقة التطبيق الصرفة (PendingRequestStore) على JVM.
    testImplementation("junit:junit:4.13.2")
    // ---- UI layer: Jetpack Compose + Material 3 (Google official) ----
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")
    implementation("androidx.core:core-ktx:1.15.0")
    // Material components for consistent, accessible native dialogs and controls.
    implementation("com.google.android.material:material:1.13.0")
    // Efficient image decoding and thumbnail loading for file grids.
    implementation("io.coil-kt:coil:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.media3:media3-exoplayer:1.10.0")
    implementation("androidx.media3:media3-session:1.10.0")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
}
