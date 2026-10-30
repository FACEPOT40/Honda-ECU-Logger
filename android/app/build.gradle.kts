plugins {
    id("com.android.application")
}

android {
    namespace = "com.pai.hondaecu"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.pai.hondaecu"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.all {
            // optional extra vectors made from real ride logs, which are never committed
            it.systemProperty("golden.file", System.getProperty("golden.file") ?: "")
        }
    }
}

dependencies {
    implementation("com.github.mik3y:usb-serial-for-android:3.11.0")
    testImplementation("junit:junit:4.13.2")
}
