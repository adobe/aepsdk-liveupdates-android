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
        // Required to resolve the aepsdk-commons Gradle plugin, referenced only by the
        // :liveupdates module via the aep-* plugin id family below.
        maven { url = uri("https://jitpack.io") }
        mavenLocal()
    }
    // The aepsdk-commons Gradle plugin does not publish plugin marker artifacts, so
    // `plugins { id("aep-library") version "..." }` cannot resolve it directly.
    // Redirect any `aep-*` plugin id to its Maven coordinate so the SDK module can keep
    // using `plugins { id("aep-library") }` without pulling commons in at the project
    // root. The testapp does not request any `aep-*` plugin, so this rule never fires
    // during its resolution.
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id.startsWith("aep-")) {
                useModule("com.github.adobe:aepsdk-commons:dev-v4.0.0-SNAPSHOT")
            }
        }
    }
    // Plugin versions consumed by both modules. Values match what aepsdk-commons v4.0.0
    // brings today, so moving off commons at the root does not change the toolchain.
    plugins {
        id("com.android.application") version "8.9.1" apply false
        id("com.android.library") version "8.9.1" apply false
        id("org.jetbrains.kotlin.android") version "2.0.21" apply false
        id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
        id("com.google.gms.google-services") version "4.4.1" apply false
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
