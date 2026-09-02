/*
  Copyright 2026 Adobe. All rights reserved.
  This file is licensed to you under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License. You may obtain a copy
  of the License at http://www.apache.org/licenses/LICENSE-2.0
  Unless required by applicable law or agreed to in writing, software distributed under
  the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR REPRESENTATIONS
  OF ANY KIND, either express or implied. See the License for the specific language
  governing permissions and limitations under the License.
*/

package com.adobe.marketing.mobile.liveupdatessample

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.adobe.marketing.mobile.Assurance
import com.adobe.marketing.mobile.Messaging
import com.adobe.marketing.mobile.MobileCore
import com.adobe.marketing.mobile.edge.identity.Identity
import com.google.firebase.messaging.FirebaseMessaging

// Blue color scheme so the app bar / buttons render blue instead of the Material3 default purple.
private val LiveUpdatesBlueColors = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF001B3E),
    secondary = Color(0xFF00639B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCFE5FF),
    onSecondaryContainer = Color(0xFF001D33)
)

/** One synced identity, as read from the Experience Platform IdentityMap. */
private data class IdentityRow(val namespace: String, val value: String, val isPrimary: Boolean)

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "LiveUpdateSample"
    }

    private var fcmToken by mutableStateOf<String?>(null)

    // Synced identities, ECID first. Populated from Identity.getIdentities(...).
    private var identities by mutableStateOf<List<IdentityRow>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // LiveUpdate wiring lives in LiveUpdatesApplication.onCreate.
        requestNotificationPermission()
        fetchFcmToken()

        // Handle a notification tap that launched (or re-fronted) this activity.
        // No-ops if the intent has no AEP tracking extras.
        fireTapTrackingIfAny(intent)

        // Handle Assurance deep-link (lutest://...?adb_validation_sessionid=...).
        startAssuranceSessionIfAny(intent)

        setContent {
            var showTopics by remember { mutableStateOf(false) }
            MaterialTheme(colorScheme = LiveUpdatesBlueColors) {
                if (showTopics) {
                    TopicsScreen(
                        fcmToken = fcmToken,
                        onBack = { showTopics = false }
                    )
                } else {
                    LiveUpdateInfoScreen(
                        fcmToken = fcmToken,
                        identities = identities,
                        onOpenTopics = { showTopics = true }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        fetchIdentities()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        fireTapTrackingIfAny(intent)
        startAssuranceSessionIfAny(intent)
    }

    /**
     * Starts an Assurance session if [incoming] is the deep-link configured for this app
     * and carries the `adb_validation_sessionid` query param. No-op for any other intent.
     */
    private fun startAssuranceSessionIfAny(incoming: Intent?) {
        val data = incoming?.data ?: return
        if (data.scheme.equals("lutest", ignoreCase = true) &&
            data.getQueryParameter("adb_validation_sessionid") != null
        ) {
            Log.d(TAG, "Starting Assurance session for $data")
            Assurance.startSession(data.toString())
        }
    }

    /**
     * Fires a push-tap tracking event if the intent carries AEP tracking extras
     * (messageId + adobe_xdm), injected upstream by [Messaging.addPushTrackingDetails].
     * Internally a no-op when those extras are absent, so it is safe to call on every
     * onCreate / onNewIntent unconditionally.
     */
    private fun fireTapTrackingIfAny(incoming: Intent?) {
        if (incoming == null) return
        Messaging.handleNotificationResponse(incoming, /* applicationOpened = */ true, /* customActionId = */ null)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
    }

    private fun fetchFcmToken() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.w(TAG, "Fetching FCM token failed", task.exception)
                return@addOnCompleteListener
            }
            val token = task.result
            fcmToken = token
            // Clearly tagged so it's easy to grep from logcat:
            //   adb logcat -s LiveUpdateSample
            Log.d(TAG, "FCM_TOKEN=$token")
        }
    }

    /**
     * Fetches the current Experience Platform identities via [Identity.getIdentities] and
     * flattens them to (namespace -> value) pairs for display, ECID first then the rest
     * (alphabetically). The callback runs on a background thread, so state is updated on
     * the main thread.
     */
    private fun fetchIdentities() {
        Identity.getIdentities { identityMap ->
            if (identityMap == null) return@getIdentities
            val ordered = mutableListOf<IdentityRow>()
            // ECID first.
            identityMap.getIdentityItemsForNamespace("ECID").forEach {
                ordered.add(IdentityRow("ECID", it.id, it.isPrimary))
            }
            // Then every other namespace (e.g. Email), alphabetically for a stable order.
            identityMap.namespaces
                .filter { it != "ECID" }
                .sorted()
                .forEach { namespace ->
                    identityMap.getIdentityItemsForNamespace(namespace).forEach {
                        ordered.add(IdentityRow(namespace, it.id, it.isPrimary))
                    }
                }
            runOnUiThread { identities = ordered }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveUpdateInfoScreen(
    fcmToken: String?,
    identities: List<IdentityRow>,
    onOpenTopics: () -> Unit
) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Live Updates") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (fcmToken != null) "✅ FCM token ready" else "⏳ Loading FCM token…",
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (fcmToken != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = fcmToken,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(onClick = { copyToClipboard(context, fcmToken, "FCM token") }) {
                            Text("Copy token")
                        }
                    }
                }
            }

            // Sync the FCM token to Adobe as the push identifier (mirrors the Messaging sample).
            Button(
                onClick = {
                    if (fcmToken.isNullOrEmpty()) {
                        Toast.makeText(context, "FCM token not ready yet.", Toast.LENGTH_SHORT).show()
                    } else {
                        MobileCore.setPushIdentifier(fcmToken)
                        Toast.makeText(context, "Synced push identifier with Adobe.", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Sync push identifier") }

            Button(onClick = onOpenTopics, modifier = Modifier.fillMaxWidth()) {
                Text("Manage FCM topics")
            }

            // Quick Connect: pairs with Assurance without a QR code / deeplink session id.
            // No-ops on non-debuggable builds or if a session is already active.
            OutlinedButton(
                onClick = { Assurance.startSession() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Connect Assurance (Quick Connect)") }

            IdentitiesCard(identities = identities)
        }
    }
}

@Composable
private fun IdentitiesCard(identities: List<IdentityRow>) {
    val context = LocalContext.current
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Synced identities", style = MaterialTheme.typography.titleMedium)
            if (identities.isEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "No identities yet.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                identities.forEach { row ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = row.namespace, style = MaterialTheme.typography.labelLarge)
                                if (row.isPrimary) {
                                    Text(
                                        text = "  •  PRIMARY",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Text(text = row.value, style = MaterialTheme.typography.bodySmall)
                        }
                        if (row.namespace == "ECID") {
                            IconButton(onClick = { copyToClipboard(context, row.value, "ECID") }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_content_copy),
                                    contentDescription = "Copy ECID",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String, label: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
}
