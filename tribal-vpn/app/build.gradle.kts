plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.tribal.vpn"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tribal.vpn"
        minSdk = 26 // required for foreground service VPN, per roadmap Phase 1
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        ndk {
            // arm64-v8a is primary target per roadmap; armeabi-v7a for legacy
            // devices, x86_64 for emulator testing.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            ndkBuild {
                // Keep the native tunnel engine optimized even in debug
                // builds - the packet relay is the hot path. AGP passes
                // APP_ABI from the ndk abiFilters above.
                arguments += listOf("NDK_DEBUG=0")
            }
        }
    }

    // Wires app/src/main/cpp/Android.mk into the Gradle build (ndk-build).
    // Upstream hev-socks5-tunnel has no CMake build, so its own Android.mk
    // builds libhev-socks5-tunnel.so from the git submodule (cloned by CI -
    // see .gitmodules at the repo root) and tribal_tunnel_bridge links
    // against it. AGP allows only one native build system per module, which
    // is why this replaced the old CMake block.
    externalNativeBuild {
        ndkBuild {
            path = file("src/main/cpp/Android.mk")
        }
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // Encrypted local storage for VPN profiles (passwords never stored in plaintext)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // JSON serialization for profile persistence
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")

    // Coroutines for traffic sampling / connection state flows
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Phase 3: SSH transport
    implementation("com.hierynomus:sshj:0.38.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // Unit tests (JVM only - see app/src/test)
    testImplementation("junit:junit:4.13.2")
}
