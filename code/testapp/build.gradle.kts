/*
 * Copyright 2026 Adobe. All rights reserved.
 * This file is licensed to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR REPRESENTATIONS
 * OF ANY KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
import com.adobe.marketing.mobile.gradle.BuildConstants

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Kotlin 2.0 ships the Compose compiler as a first-party plugin; no version needed
    // because it resolves from the same Kotlin 2.0.21 distribution already on the classpath.
    id("org.jetbrains.kotlin.plugin.compose")
    // Reads google-services.json next to this file to configure Firebase project / app id.
    id("com.google.gms.google-services")
}

android {
    namespace = "com.adobe.marketing.mobile.liveupdatessample"

    defaultConfig {
        applicationId = "com.adobe.marketing.mobile.liveupdatessample"
        compileSdk = BuildConstants.Versions.COMPILE_SDK_VERSION
        minSdk = BuildConstants.Versions.MIN_SDK_VERSION
        targetSdk = BuildConstants.Versions.TARGET_SDK_VERSION
        versionCode = BuildConstants.Versions.VERSION_CODE
        versionName = BuildConstants.Versions.VERSION_NAME
    }

    kotlinOptions {
        jvmTarget = BuildConstants.Versions.KOTLIN_JVM_TARGET
    }

    buildTypes {
        getByName(BuildConstants.BuildTypes.RELEASE) {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        // No composeOptions / kotlinCompilerExtensionVersion needed with Kotlin 2.0 plugin
    }
}

val mavenCoreVersion: String by project
val mavenEdgeVersion: String by project

dependencies {
    // New Live Updates SDK under construction
    implementation(project(":liveupdates"))

    implementation("com.adobe.marketing.mobile:core:$mavenCoreVersion")
    implementation("com.github.adobe:aepsdk-messaging-android:rc-liveupdates-SNAPSHOT")
    implementation("com.adobe.marketing.mobile:edge:$mavenEdgeVersion")
    implementation("com.adobe.marketing.mobile:lifecycle:3.0.2")
    implementation("com.adobe.marketing.mobile:edgeidentity:3.0.1")
    implementation("com.adobe.marketing.mobile:assurance:3.0.7")

    // Firebase messaging — required by MessagingService (extends FirebaseMessagingService)
    implementation("com.google.firebase:firebase-messaging:23.4.1")

    // Compose BOM + UI
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.activity:activity-compose")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
