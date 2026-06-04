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
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        mavenLocal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        mavenLocal()
        maven { url = uri("https://central.sonatype.com/repository/maven-snapshots/") }
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "aepsdk-liveupdates-android"
include(":testapp", ":liveupdates")

// Composite build: the messaging repo is included here per the original intent of consuming
// messaging from live source code. In practice, composite build substitution does not fire
// because:
//   1. The aep-library plugin sets groupId inside the Maven publication but does not set
//      project.group, so Gradle cannot auto-detect the com.adobe.marketing.mobile:messaging
//      → :messaging substitution.
//   2. Adding an explicit substitution rule triggers AGP's AgpVersionCompatibilityRule, which
//      rejects mixing AGP 8.2.0 (messaging / commons 3.x) with AGP 8.9.1 (this SDK / commons
//      4.0.0) inside a single composite build.
// As a result, com.adobe.marketing.mobile:messaging resolves from Maven Central via the BOM.
// TODO: investigate if an AGP attribute compatibility override can make local-source substitution
//       work, or switch to publishToMavenLocal as the live-source workflow for messaging.
includeBuild("../../aepsdk-messaging-android/code")
