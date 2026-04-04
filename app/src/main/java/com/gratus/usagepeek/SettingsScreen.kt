package com.gratus.usagepeek

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {

    val context = LocalContext.current
    val pm = context.packageManager

    // Observe States from ViewModel
    val overlayRunning by viewModel.isServiceRunning.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val enabledPkgs by viewModel.enabledPkgs.collectAsState()
    val filteredApps by viewModel.filteredApps.collectAsState()

    // Local UI States (Permissions)
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var usageGranted by remember { mutableStateOf(PermissionUtils.hasUsageAccess(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_RESUME) {
                overlayGranted = Settings.canDrawOverlays(context)
                usageGranted = PermissionUtils.hasUsageAccess(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 16.dp, bottom = 6.dp, top = 8.dp, start = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("UsagePeek", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/spewedprojects/UsagePeek"))) }) {
                Icon(painterResource(R.drawable.github_mark), contentDescription = "GitHub", Modifier.size(45.dp).padding(2.dp))
            }
        }

        // Permissions
        if (!overlayGranted) {
            PermissionCard(title = "Display over other apps", granted = false) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri()))
            }
        }
        if (!usageGranted) {
            PermissionCard(title = "App usage access", granted = false) {
                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }

        Spacer(Modifier.height(1.dp))

        // Search Bar
        val view = LocalView.current
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val focusManager = LocalFocusManager.current

        // This prevents the keyboard from jumping up on launch
        LaunchedEffect(Unit) {
            focusManager.clearFocus()
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { viewModel.onSearchQueryChange(it) },
            label = { Text("Search apps") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp, bottom = 2.dp, start = 16.dp, end = 16.dp)
                .defaultMinSize(minHeight = 1.dp),
            shape = RoundedCornerShape(50),
            singleLine = true,
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            viewModel.onSearchQueryChange("")
                            // Hide keyboard and remove cursor focus
                            imm.hideSoftInputFromWindow(view.windowToken, 0)
                            focusManager.clearFocus()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Clear search"
                        )
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                cursorColor = MaterialTheme.colorScheme.primary
            )
        )

        // App List
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(
                items = filteredApps,
                key = { app -> "${app.activityInfo.packageName}_${app.activityInfo.name}_${app.userHandle.hashCode()}" }
            ) { app ->
                val pkg = app.activityInfo.packageName

                // Icon loading stays in the UI layer as it generates UI components (Bitmap)
                val icon = remember(pkg, app.uid) {
                    pm.getUserBadgedIcon(pm.getApplicationIcon(app.activityInfo.applicationInfo), app.userHandle)
                        .toBitmap().asImageBitmap()
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.toggleApp(pkg, pkg !in enabledPkgs) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(painter = BitmapPainter(icon), contentDescription = null, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(text = app.label, color = MaterialTheme.colorScheme.onBackground)
                        if (app.isTwin) {
                            Text("Duplicate App", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Switch(
                        checked = pkg in enabledPkgs,
                        onCheckedChange = { viewModel.toggleApp(pkg, it) }
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }

        // Bottom Controls
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 0.dp, top = 0.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                enabled = overlayGranted && !overlayRunning,
                onClick = { viewModel.startService() }
            ) { Text("Start timer") }

            Button(
                enabled = overlayRunning,
                onClick = { viewModel.stopService() }
            ) { Text("Stop") }

            Spacer(Modifier.weight(1f))

            OutlinedButton(
                onClick = { viewModel.exportDatabase(context) }
            ) { Text("Save DB") }
        }
    }
}

@Composable
private fun PermissionCard(title: String, granted: Boolean, onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().padding(4.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title)
            Icon(imageVector = if (granted) Icons.Default.Check else Icons.Default.Close, contentDescription = null)
        }
    }
}