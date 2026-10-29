import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
}

// ---------------------------------------------------------------------------------------------
// Release signing: read from <repo>/signing.properties (storeFile, storePassword, keyAlias,
// keyPassword). The environment variables KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS and
// KEY_PASSWORD override the file value by value (used by the GitHub Actions workflow).
// A relative storeFile is resolved against the repository root.
// ---------------------------------------------------------------------------------------------
val signingProps = Properties().apply {
    val file = rootProject.file("signing.properties")
    if (file.isFile) {
        file.inputStream().use { load(it) }
    }
}

fun signingValue(envName: String, propName: String): String? {
    val fromEnv = System.getenv(envName)?.trim()
    if (!fromEnv.isNullOrEmpty()) return fromEnv
    val fromFile = signingProps.getProperty(propName)?.trim()
    return if (fromFile.isNullOrEmpty()) null else fromFile
}

val releaseStoreFile: File? = signingValue("KEYSTORE_FILE", "storeFile")?.let { rootProject.file(it) }
val releaseStorePassword: String? = signingValue("KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias: String? = signingValue("KEY_ALIAS", "keyAlias")
val releaseKeyPassword: String? = signingValue("KEY_PASSWORD", "keyPassword") ?: releaseStorePassword
val hasReleaseSigning: Boolean = releaseStoreFile?.isFile == true &&
    releaseStorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null

if (!hasReleaseSigning) {
    logger.warn("Jobbdialer: no usable release signing configuration found; the release APK will be unsigned.")
}

android {
    namespace = "se.jobbdialer"
    compileSdk = 35

    defaultConfig {
        applicationId = "se.jobbdialer"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // targetSdk 34 is a deliberate choice for a sideloaded app. Current lint versions report
        // "ExpiredTargetSdkVersion" (a Google Play rule) as a fatal error, which would make the
        // lintVital step abort assembleRelease. Lint can still be run manually with ./gradlew lint.
        disable += "ExpiredTargetSdkVersion"
        disable += "ExpiringTargetSdkVersion"
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    // Intentionally empty: framework APIs only (android.*, java.*, org.json).
}
