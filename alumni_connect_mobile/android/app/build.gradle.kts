import java.util.Properties

plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

val releaseKeyProperties = Properties().apply {
    val propertiesFile = rootProject.file("key.properties")
    if (propertiesFile.exists()) propertiesFile.inputStream().use(::load)
}

fun releaseSigningValue(propertyName: String, gradlePropertyName: String, environmentName: String): String? =
    System.getenv(environmentName)?.takeIf(String::isNotBlank)
        ?: providers.gradleProperty(gradlePropertyName).orNull?.takeIf(String::isNotBlank)
        ?: releaseKeyProperties.getProperty(propertyName)?.takeIf(String::isNotBlank)

val releaseStorePath = releaseSigningValue("storeFile", "androidReleaseStoreFile", "ANDROID_RELEASE_STORE_FILE")
val releaseStorePassword = releaseSigningValue("storePassword", "androidReleaseStorePassword", "ANDROID_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseSigningValue("keyAlias", "androidReleaseKeyAlias", "ANDROID_RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseSigningValue("keyPassword", "androidReleaseKeyPassword", "ANDROID_RELEASE_KEY_PASSWORD")
val releaseStore = releaseStorePath?.let(rootProject::file)
val releaseSigningConfigured = !releaseStorePath.isNullOrBlank()
    && releaseStore?.isFile == true
    && !releaseStorePassword.isNullOrBlank()
    && !releaseKeyAlias.isNullOrBlank()
    && !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "com.example.alumni_connect_mobile"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_11.toString()
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.example.alumni_connect_mobile"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    signingConfigs {
        create("release") {
            if (releaseSigningConfigured) {
                storeFile = releaseStore
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

gradle.taskGraph.whenReady {
    val releaseSigningTasks = setOf(
        "assembleRelease", "bundleRelease", "packageRelease", "validateSigningRelease", "signReleaseBundle",
    )
    if (allTasks.any { it.name in releaseSigningTasks } && !releaseSigningConfigured) {
        throw GradleException(
            "Release signing is not configured. Add android/key.properties (ignored by Git), " +
                "set ANDROID_RELEASE_STORE_FILE/STORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD, or provide the " +
                "matching androidRelease* Gradle properties. A release build will not use the debug key."
        )
    }
}

flutter {
    source = "../.."
}
