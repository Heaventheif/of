pluginManagement {
    repositories {
        // مرايا Aliyun أولاً: Maven Central يحد المعدل (429/502) من هذا الصندوق.
        // ترتيب google قبل central: أدوات AGP (com.android.tools.*) على google-maven.
        // البوابات الأصلية تبقى احتياطاً والتحقق (verification-metadata) يظل مطبقاً.
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        google()
        mavenCentral()
    }
}
rootProject.name = "OpenFileManager"
include(":app")
include(":core")
