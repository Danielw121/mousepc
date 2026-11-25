plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mousepc.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mousepc.app"
        minSdk = 26
        targetSdk = 35
        // En GitHub Actions la versión sale de la etiqueta (v1.2.3 -> 1.2.3).
        versionName = System.getenv("MOUSEPC_VERSION") ?: "1.0.0"
        versionCode = versionName!!.split(".").map { it.toIntOrNull() ?: 0 }
            .let { (it + listOf(0, 0, 0)).take(3) }
            .let { (maj, min, pat) -> maj * 10000 + min * 100 + pat }
    }

    // Firma de publicación: se toma de variables de entorno (en GitHub, de los Secrets del repo).
    // Sin ellas se usa la clave de depuración, que sirve para probar en tu propio teléfono.
    val keystorePath = System.getenv("MOUSEPC_KEYSTORE")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("MOUSEPC_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MOUSEPC_KEY_ALIAS") ?: "mousepc"
                keyPassword = System.getenv("MOUSEPC_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.04.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
}
