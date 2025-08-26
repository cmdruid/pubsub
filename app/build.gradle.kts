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
        versionName = "0.9.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // Local signing (when keystore file exists)
            val keystoreFile = rootProject.file("pubsub-release.keystore")
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                
                // Load environment variables from .env file if it exists
                val envFile = rootProject.file(".env")
                val envMap = mutableMapOf<String, String>()
                
                if (envFile.exists()) {
                    envFile.readLines().forEach { line ->
                        if (line.isNotBlank() && !line.startsWith("#") && line.contains("=")) {
                            val (key, value) = line.split("=", limit = 2)
                            envMap[key.trim()] = value.trim()
                        }
                    }
                }
                
                // Get passwords from .env file, environment variables, or project properties
                storePassword = envMap["KEYSTORE_PASSWORD"] 
                    ?: System.getenv("KEYSTORE_PASSWORD") 
                    ?: project.findProperty("KEYSTORE_PASSWORD") as String? ?: ""
                keyAlias = envMap["KEY_ALIAS"] 
                    ?: System.getenv("KEY_ALIAS") 
                    ?: project.findProperty("KEY_ALIAS") as String? ?: ""
                keyPassword = envMap["KEY_PASSWORD"] 
                    ?: System.getenv("KEY_PASSWORD") 
                    ?: project.findProperty("KEY_PASSWORD") as String? ?: ""

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
    
    // Configure custom APK/AAB names with version
    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val buildType = variant.buildType.name
            val versionName = variant.versionName
            
            // For debug builds, versionName already includes "-debug" suffix
            // For release builds, we want to add the buildType
            val fileName = if (buildType == "debug" && versionName?.endsWith("-debug") == true) {
                "pubsub-${versionName}.apk"
            } else {
                "pubsub-${versionName}-${buildType}.apk"
            }
            
            output.outputFileName = fileName
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
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    testImplementation("com.google.truth:truth:1.1.5")
    testImplementation("org.robolectric:robolectric:4.11.1")
    
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("io.mockk:mockk-android:1.13.8")
}
