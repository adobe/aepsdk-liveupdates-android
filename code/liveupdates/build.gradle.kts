import com.adobe.marketing.mobile.gradle.BuildConstants

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
plugins {
    id("aep-library")
}

val mavenCoreVersion: String by project
val mavenEdgeVersion: String by project

aepLibrary {
    namespace = "com.adobe.marketing.mobile.liveupdates"
    enableSpotless = true
    enableCheckStyle = true
    enableDokkaDoc = true
    compose = true

    publishing {
        gitRepoName = "aepsdk-liveupdates-android"
        addCoreDependency(mavenCoreVersion)
        addEdgeDependency(mavenEdgeVersion)

        addMavenDependency("org.jetbrains.kotlin", "kotlin-stdlib-jdk8", BuildConstants.Versions.KOTLIN)
        addMavenDependency("androidx.appcompat", "appcompat", BuildConstants.Versions.ANDROIDX_APPCOMPAT)
        addMavenDependency("androidx.compose.runtime", "runtime", BuildConstants.Versions.COMPOSE)
        addMavenDependency("androidx.activity", "activity-compose", BuildConstants.Versions.ANDROIDX_ACTIVITY_COMPOSE)
    }
}

dependencies {
    implementation("com.adobe.marketing.mobile:core:$mavenCoreVersion")
}
