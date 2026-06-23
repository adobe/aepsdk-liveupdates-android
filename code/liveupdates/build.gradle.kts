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
val mavenMessagingVersion: String by project

aepLibrary {
    namespace = "com.adobe.marketing.mobile.messaging.liveupdate"
    enableSpotless = true
    enableSpotlessPrettierForJava = true
    enableCheckStyle = true
    enableDokkaDoc = true
    // compose = false (default) — the SDK has no UI of its own

    publishing {
        gitRepoName = "aepsdk-liveupdates-android"
        addCoreDependency(mavenCoreVersion)
        addEdgeDependency(mavenEdgeVersion)
        addMavenDependency("com.adobe.marketing.mobile", "messaging", mavenMessagingVersion)
    }
}

dependencies {
    // Live Update APIs: NotificationCompat.ProgressStyle, setRequestPromotedOngoing,
    // NotificationManagerCompat — all in androidx.core 1.17.0 (MANDATORY)
    implementation(BuildConstants.Dependencies.ANDROIDX_CORE_KTX)

    // AEP extension registration and Event Hub — sourced from mavenLocal at 3.8.0
    implementation("com.adobe.marketing.mobile:core:$mavenCoreVersion")

    // Messaging — required for ILiveUpdateHandler interface, Messaging.setLiveUpdateHandler,
    // MessagingConstants. Sourced from mavenLocal at 3.10.0.
    implementation("com.adobe.marketing.mobile:messaging:$mavenMessagingVersion")

    // Edge Network for Live Update lifecycle telemetry
    implementation("com.adobe.marketing.mobile:edge:$mavenEdgeVersion")

    // Firebase Messaging — needed for the RemoteMessage type referenced in
    // ILiveUpdateHandler.handleLiveUpdatePush(...) and in LiveUpdatePayload.parse(...).
    // Marked compileOnly to match Messaging's pattern: the consuming app provides
    // firebase-messaging on the runtime classpath; the SDK only needs the type at
    // compile time.
    compileOnly("com.google.firebase:firebase-messaging:23.4.1")
}
