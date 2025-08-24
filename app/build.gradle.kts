import java.util.Base64
import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cmdruid.pubsub"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cmdruid.pubsub"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.9.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // Local signing (when keystore file exists)
            val keystoreFile = rootProject.file("pubsub-release.keystore")
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = project.findProperty("KEYSTORE_PASSWORD") as String? ?: ""
                keyAlias = project.findProperty("KEY_ALIAS") as String? ?: ""
                keyPassword = project.findProperty("KEY_PASSWORD") as String? ?: ""
            } 
            // CI/CD signing (using environment variables)
            else if (System.getenv("KEYSTORE_BASE64") != null) {
                val keystoreData = System.getenv("KEYSTORE_BASE64")
                val keystoreBytes = Base64.getDecoder().decode(keystoreData)
                val keystoreTempFile = File.createTempFile("keystore", ".keystore")
                keystoreTempFile.writeBytes(keystoreBytes)
                
                storeFile = keystoreTempFile
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: ""
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isDebuggable = true
        }
        
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    
    kotlinOptions {
        jvmTarget = "21"
    }
    
    // Configure Java 21 compatibility
    androidResources {
        noCompress += "txt"
    }
    
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    
    bundle {
        language {
            enableSplit = false
        }
    }
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    
    // Splash Screen API
    implementation("androidx.core:core-splashscreen:1.0.1")
    
    // RecyclerView for lists
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    
    // Local broadcasts
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    
    // WebSocket and HTTP
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")
    
    // Coroutines for async operations
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // Notifications
    implementation("androidx.core:core:1.12.0")
    
    // Lifecycle components
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    
    // Enhanced Nostr implementation with Quartz-inspired patterns
    
    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
