import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

fun apiBaseUrl(environment: String): String {
    val configFile = rootProject.file("config/api-$environment.properties")
    val properties = Properties().apply {
        configFile.inputStream().use(::load)
    }
    return requireNotNull(properties.getProperty("coreApiBaseUrl")) {
        "coreApiBaseUrl is required in ${configFile.path}"
    }.also { url ->
        require(url.endsWith("/")) { "coreApiBaseUrl must end with /" }
    }
}

fun authProperties(environment: String): Properties {
    val configFile = rootProject.file("config/auth-$environment.properties")
    return Properties().apply { configFile.inputStream().use(::load) }
}

android {
    namespace = "com.yoshiki.lifeagent"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.yoshiki.lifeagent"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            buildConfigField("String", "CORE_API_BASE_URL", "\"${apiBaseUrl("debug")}\"")
            authProperties("debug").forEach { key, value ->
                buildConfigField("String", key.toString(), "\"$value\"")
            }
            buildConfigField("boolean", "USE_FIREBASE_AUTH_EMULATOR", "true")
        }
        release {
            buildConfigField("String", "CORE_API_BASE_URL", "\"${apiBaseUrl("release")}\"")
            authProperties("release").forEach { key, value ->
                buildConfigField("String", key.toString(), "\"$value\"")
            }
            buildConfigField("boolean", "USE_FIREBASE_AUTH_EMULATOR", "false")
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
}

dependencies {
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
