plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "com.sonozaki.bedatastore"
    compileSdk = 35

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    api(libs.datastore.core.android)
    api(libs.datastore.preferences)
    testImplementation(libs.junit)
}

publishing {
    publications {
        create("release", MavenPublication::class) {
            groupId = "com.github.bakad3v"
            artifactId = "bedatastore"
            version = "1.1.0-alpha"

            afterEvaluate {
                from(components["release"])
            }
        }
    }
}
