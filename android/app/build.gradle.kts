import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing key for GitHub Releases: android/keystore.properties (never in git, see
// keystore.properties.example) points to the keystore file, which is kept outside the project.
// Without it, debug builds are unchanged and a release build comes out unsigned.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.pai.hondaecu"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.pai.hondaecu"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "0.3.0"
    }
    signingConfigs {
        if (!keystoreProps.isEmpty) create("release") {
            storeFile = file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword") ?: keystoreProps.getProperty("storePassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false     // small app; keeps usb-serial-for-android free of shrinker surprises
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.all {
            // optional extra vectors made from real ride logs, which are never committed
            it.systemProperty("golden.file", System.getProperty("golden.file") ?: "")
            it.systemProperty("ride.file", System.getProperty("ride.file") ?: "")
        }
    }
}

dependencies {
    implementation("com.github.mik3y:usb-serial-for-android:3.11.0")
    testImplementation("junit:junit:4.13.2")
}
