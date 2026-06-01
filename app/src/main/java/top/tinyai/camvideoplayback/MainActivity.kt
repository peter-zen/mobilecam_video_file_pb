package top.tinyai.camvideoplayback

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.json.JSONArray
import top.tinyai.camvideoplayback.ui.theme.CamVideoPlaybackTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CamVideoPlaybackTheme {
                MainScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("cam_playback", Context.MODE_PRIVATE) }

    var ip by remember { mutableStateOf(prefs.getString("last_ip", "192.168.1.1") ?: "192.168.1.1") }
    var filename by remember { mutableStateOf(prefs.getString("last_filename", "Go5_000001_20260522_165437.MP4") ?: "Go5_000001_20260522_165437.MP4") }

    val ipHistory = remember { loadHistory(prefs, "ip_history") }
    val fileHistory = remember { loadHistory(prefs, "file_history") }

    var ipExpanded by remember { mutableStateOf(false) }
    var fileExpanded by remember { mutableStateOf(false) }

    // WiFi connection state
    val wifiSsid = "Guimeet_8C0055"
    val wifiPassword = "1234567890"
    var wifiStatus by remember { mutableStateOf("未连接") }
    var wifiConnecting by remember { mutableStateOf(false) }
    val handler = remember { Handler(Looper.getMainLooper()) }
    var wifiNetworkCallback by remember { mutableStateOf<ConnectivityManager.NetworkCallback?>(null) }

    // Get current WiFi SSID
    fun getCurrentSsid(): String? {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val info = wm.connectionInfo ?: return null
        val ssid = info.ssid
        return if (ssid != null && ssid != "<unknown ssid>" && ssid != "0x") {
            ssid.removeSurrounding("\"")
        } else null
    }

    fun cleanupWifi() {
        wifiNetworkCallback?.let {
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        wifiNetworkCallback = null
    }

    @Suppress("NewApi")
    fun connectWifi() {
        cleanupWifi()

        val currentSsid = getCurrentSsid()
        if (currentSsid == wifiSsid) {
            wifiStatus = "已连接到 $wifiSsid"
            return
        }

        wifiConnecting = true
        wifiStatus = "请确认系统弹出的连接对话框..."

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(wifiSsid)
            .setWpa2Passphrase(wifiPassword)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                // Bind entire process to this network so SDK sockets use it
                cm.bindProcessToNetwork(network)
                handler.post {
                    wifiConnecting = false
                    wifiStatus = "已连接到 $wifiSsid"
                }
            }

            override fun onUnavailable() {
                super.onUnavailable()
                handler.post {
                    wifiConnecting = false
                    wifiStatus = "连接失败，请手动连接 WiFi"
                }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                handler.post {
                    wifiConnecting = false
                    wifiStatus = "WiFi 已断开"
                }
            }
        }

        wifiNetworkCallback = callback
        try {
            cm.requestNetwork(request, callback, 20_000)
        } catch (e: SecurityException) {
            wifiConnecting = false
            wifiStatus = "权限不足: ${e.message}"
        } catch (e: IllegalArgumentException) {
            wifiConnecting = false
            wifiStatus = "请求无效: ${e.message}"
        }
    }

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            connectWifi()
        } else {
            wifiStatus = "需要位置权限才能连接 WiFi"
        }
    }

    // Cleanup on dispose
    DisposableEffect(Unit) {
        onDispose { cleanupWifi() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Camera Video Playback") }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Enter camera IP and video filename to play.",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            // IP Input with history dropdown
            Column {
                OutlinedTextField(
                    value = ip,
                    onValueChange = {
                        ip = it
                        ipExpanded = it.isNotEmpty() && ipHistory.any { h -> h.contains(it, ignoreCase = true) && h != it }
                    },
                    label = { Text("Camera IP") },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (ipHistory.isNotEmpty()) {
                            Text(
                                text = "▼",
                                modifier = Modifier
                                    .padding(end = 12.dp)
                                    .clickable { ipExpanded = true }
                            )
                        }
                    }
                )
                DropdownMenu(
                    expanded = ipExpanded,
                    onDismissRequest = { ipExpanded = false },
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    ipHistory.filter { it.contains(ip, ignoreCase = true) && it != ip }.forEach { item ->
                        DropdownMenuItem(
                            text = { Text(item) },
                            onClick = {
                                ip = item
                                ipExpanded = false
                            }
                        )
                    }
                }
            }

            // Filename Input with history dropdown
            Column {
                OutlinedTextField(
                    value = filename,
                    onValueChange = {
                        filename = it
                        fileExpanded = it.isNotEmpty() && fileHistory.any { h -> h.contains(it, ignoreCase = true) && h != it }
                    },
                    label = { Text("Filename (e.g. Go5_0001.MP4)") },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (fileHistory.isNotEmpty()) {
                            Text(
                                text = "▼",
                                modifier = Modifier
                                    .padding(end = 12.dp)
                                    .clickable { fileExpanded = true }
                            )
                        }
                    }
                )
                DropdownMenu(
                    expanded = fileExpanded,
                    onDismissRequest = { fileExpanded = false },
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    fileHistory.filter { it.contains(filename, ignoreCase = true) && it != filename }.forEach { item ->
                        DropdownMenuItem(
                            text = { Text(item) },
                            onClick = {
                                filename = item
                                fileExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // WiFi connect button + status
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        val perms = arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION
                        )
                        val hasPermission = perms.all {
                            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                        }
                        if (hasPermission) {
                            connectWifi()
                        } else {
                            permissionLauncher.launch(perms)
                        }
                    },
                    enabled = !wifiConnecting,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text("连接 WiFi")
                }
                Text(
                    text = wifiStatus,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        wifiStatus.startsWith("已连接") -> MaterialTheme.colorScheme.primary
                        wifiStatus.startsWith("连接中") || wifiStatus.startsWith("请确认") -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.error
                    }
                )
            }

            // Show the full path that will be used
            if (filename.isNotBlank()) {
                Text(
                    text = "SDK path: /DCIM/$filename",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    saveHistory(prefs, "ip_history", ip)
                    saveHistory(prefs, "file_history", filename)
                    prefs.edit().putString("last_ip", ip).putString("last_filename", filename).apply()

                    val intent = Intent(context, PlaybackActivity::class.java).apply {
                        putExtra(PlaybackActivity.EXTRA_IP, ip)
                        putExtra(PlaybackActivity.EXTRA_FILENAME, filename)
                    }
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = ip.isNotBlank() && filename.isNotBlank()
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play")
                Spacer(modifier = Modifier.width(8.dp))
                Text("Play")
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "SDK: MobileCamSDK (JAR + .so)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Protocol: RTSP over port 554",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Video path prefix: /DCIM/",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun loadHistory(prefs: android.content.SharedPreferences, key: String): List<String> {
    val json = prefs.getString(key, "[]") ?: "[]"
    return try {
        val arr = JSONArray(json)
        List(arr.length()) { arr.getString(it) }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun saveHistory(prefs: android.content.SharedPreferences, key: String, value: String) {
    if (value.isBlank()) return
    val existing = loadHistory(prefs, key).toMutableList()
    existing.remove(value)
    existing.add(0, value)
    val trimmed = existing.take(10)
    val arr = JSONArray()
    trimmed.forEach { arr.put(it) }
    prefs.edit().putString(key, arr.toString()).apply()
}
