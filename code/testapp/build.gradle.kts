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
        languageVersion = BuildConstants.Versions.KOTLIN_LANGUAGE_VERSION
        apiVersion = BuildConstants.Versions.KOTLIN_API_VERSION
    }

    buildTypes {
        getByName(BuildConstants.BuildTypes.RELEASE) {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    // New SDK under construction, via project reference.
    implementation(project(":liveupdates"))

    // AEP SDK BOM pins versions for core/lifecycle/edge/edgeidentity/assurance.
    // messaging is declared here but auto-substituted with the local includeBuild source.
    implementation(platform("com.adobe.marketing.mobile:sdk-bom:3.13.0"))

    implementation("com.adobe.marketing.mobile:messaging")
    implementation("com.adobe.marketing.mobile:core")
    implementation("com.adobe.marketing.mobile:lifecycle")
    implementation("com.adobe.marketing.mobile:edge")
    implementation("com.adobe.marketing.mobile:edgeidentity")
    implementation("com.adobe.marketing.mobile:assurance")

    implementation("androidx.appcompat:appcompat:1.6.1")
}
