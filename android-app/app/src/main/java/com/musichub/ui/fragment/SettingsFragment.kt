package com.musichub.ui.fragment

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import com.musichub.MusicHubApplication
import com.musichub.R
import com.musichub.data.backup.BackupManager
import com.musichub.remote.RemoteClient
import com.musichub.remote.RemoteMode
import com.musichub.remote.RemoteServerService
import com.musichub.service.FloatingWindowService
import com.musichub.service.MediaMonitorService
import com.musichub.service.PlayerAccessibilityService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsFragment : PreferenceFragmentCompat() {

    private val connectionListener: (Boolean) -> Unit = { connected ->
        if (isAdded && view != null) {
            updateRemoteStatus()
        }
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val backupManager = BackupManager(
                (requireActivity().application as MusicHubApplication).database
            )
            try {
                val result = backupManager.export(requireContext(), uri)
                Toast.makeText(
                    requireContext(),
                    "已导出 ${result.songCount} 首歌曲, ${result.playlistCount} 个歌单",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        AlertDialog.Builder(requireContext())
            .setTitle("导入数据")
            .setMessage("将向当前曲库追加歌曲并新建歌单（已存在的歌曲会被复用，不会重复添加）。是否继续？")
            .setPositiveButton("导入") { _, _ ->
                lifecycleScope.launch {
                    val backupManager = BackupManager(
                        (requireActivity().application as MusicHubApplication).database
                    )
                    try {
                        val r = backupManager.import(requireContext(), uri)
                        AlertDialog.Builder(requireContext())
                            .setTitle("导入完成")
                            .setMessage(
                                "新增歌曲: ${r.songsAdded}\n" +
                                "已存在歌曲: ${r.songsExisting}\n" +
                                "新建歌单: ${r.playlistsAdded}\n" +
                                "歌单条目: ${r.itemsAdded}" +
                                if (r.itemsSkipped > 0) "\n跳过条目: ${r.itemsSkipped}" else ""
                            )
                            .setPositiveButton("确定", null)
                            .show()
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "导入失败: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        // Floating window preference
        findPreference<SwitchPreferenceCompat>("floating_window")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue == true) {
                    if (Settings.canDrawOverlays(requireContext())) {
                        FloatingWindowService.start(requireContext())
                    } else {
                        // Request overlay permission
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION
                        )
                        startActivity(intent)
                        false // Don't update the preference yet
                    }
                } else {
                    FloatingWindowService.stop(requireContext())
                }
                true
            }
        }

        // Notification access preference
        findPreference<Preference>("notification_access")?.apply {
            setOnPreferenceClickListener {
                if (!MediaMonitorService.isEnabled(requireContext())) {
                    MediaMonitorService.openSettings(requireContext())
                }
                true
            }
        }

        // Accessibility service preference
        findPreference<Preference>("accessibility_access")?.apply {
            setOnPreferenceClickListener {
                if (!PlayerAccessibilityService.isEnabled(requireContext())) {
                    PlayerAccessibilityService.openSettings(requireContext())
                }
                true
            }
        }

        // Write settings permission (for auto-rotate toggle workaround)
        findPreference<Preference>("write_settings_access")?.apply {
            setOnPreferenceClickListener {
                if (!Settings.System.canWrite(requireContext())) {
                    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                        data = Uri.parse("package:${requireContext().packageName}")
                    }
                    startActivity(intent)
                }
                true
            }
        }

        // Usage stats permission (for double-click navigation)
        findPreference<Preference>("usage_stats_access")?.apply {
            setOnPreferenceClickListener {
                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                startActivity(intent)
                true
            }
        }

        // Remote control mode preference
        findPreference<ListPreference>("remote_mode")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                handleRemoteModeChange(newValue as String)
                true
            }
        }

        // Remote server IP preference
        findPreference<EditTextPreference>("remote_server_ip")?.apply {
            isVisible = RemoteMode.isController()
            setOnPreferenceChangeListener { _, newValue ->
                val ip = (newValue as String).trim()
                if (RemoteMode.isController()) {
                    RemoteClient.disconnect()
                    if (ip.isNotEmpty()) {
                        RemoteMode.setController(ip)
                        RemoteClient.connect()
                        Toast.makeText(requireContext(), "正在连接到 $ip ...", Toast.LENGTH_SHORT).show()
                    }
                }
                summary = if (ip.isNotEmpty()) "服务器地址: $ip" else "输入播放手机的IP地址"
                true
            }
        }

        // Manual reconnect (visible only in controller mode)
        findPreference<Preference>("remote_reconnect_now")?.apply {
            isVisible = RemoteMode.isController()
            setOnPreferenceClickListener {
                if (RemoteMode.serverHost.isBlank()) {
                    Toast.makeText(requireContext(), "请先设置服务器地址", Toast.LENGTH_SHORT).show()
                } else {
                    RemoteClient.forceReconnect()
                    Toast.makeText(
                        requireContext(),
                        R.string.remote_reconnecting_toast_cn,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                true
            }
        }

        // Skip log preference
        findPreference<Preference>("skip_log")?.apply {
            setOnPreferenceClickListener {
                findNavController().navigate(R.id.action_settings_to_skip_log)
                true
            }
        }

        // Delete all songs preference
        findPreference<Preference>("delete_all_songs")?.apply {
            setOnPreferenceClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle("删除所有歌曲")
                    .setMessage("确定要删除所有歌曲吗？此操作无法撤销。")
                    .setPositiveButton("删除") { _, _ ->
                        lifecycleScope.launch {
                            val repository = (requireActivity().application as MusicHubApplication).repository
                            val count = repository.deleteAllSongs()
                            Toast.makeText(requireContext(), "已删除 $count 首歌曲", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
                true
            }
        }

        findPreference<Preference>("data_export")?.setOnPreferenceClickListener {
            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            exportLauncher.launch("musichub-backup-$timestamp.json")
            true
        }

        findPreference<Preference>("data_import")?.setOnPreferenceClickListener {
            importLauncher.launch(arrayOf("application/json", "*/*"))
            true
        }

        findPreference<Preference>("version")?.summary = appVersionSummary()

        // Add connection listener
        RemoteClient.addConnectionListener(connectionListener)
    }

    private fun appVersionSummary(): String {
        val ctx = requireContext()
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION") info.versionCode.toLong()
        }
        return "${info.versionName} ($code)"
    }

    private fun handleRemoteModeChange(mode: String) {
        // Stop any existing remote services
        RemoteClient.disconnect()
        // Only stop server if it was actually running to avoid race condition
        // where stop's stopSelf() races with start's startForeground()
        if (RemoteMode.isPlayer()) {
            RemoteServerService.stop(requireContext())
        }

        when (mode) {
            "standalone" -> {
                RemoteMode.setStandalone()
                Toast.makeText(requireContext(), "已切换为独立运行模式", Toast.LENGTH_SHORT).show()
            }
            "player" -> {
                RemoteMode.setPlayer()
                RemoteServerService.start(requireContext())
                Toast.makeText(requireContext(), "服务器已启动", Toast.LENGTH_SHORT).show()
            }
            "controller" -> {
                // Set controller mode first; connection happens when user enters IP
                val ip = findPreference<EditTextPreference>("remote_server_ip")?.text?.trim() ?: ""
                if (ip.isNotEmpty()) {
                    RemoteMode.setController(ip)
                    RemoteClient.connect()
                    Toast.makeText(requireContext(), "正在连接到 $ip ...", Toast.LENGTH_SHORT).show()
                } else {
                    RemoteMode.setController("")
                    Toast.makeText(requireContext(), "请输入服务器地址后自动连接", Toast.LENGTH_SHORT).show()
                }
            }
        }
        updateRemoteStatus()
        updateRemoteModeSummary(mode)
    }

    private fun updateRemoteModeSummary(mode: String) {
        findPreference<ListPreference>("remote_mode")?.summary = when (mode) {
            "player" -> {
                val ip = RemoteServerService.getDeviceIpAddress()
                "当前模式: 播放端 (IP: $ip:${RemoteMode.DEFAULT_PORT})"
            }
            "controller" -> "当前模式: 控制端"
            else -> "当前模式: 独立运行"
        }
    }

    private fun updateRemoteStatus() {
        findPreference<Preference>("remote_status")?.summary = when {
            RemoteMode.isPlayer() -> {
                val ip = RemoteServerService.getDeviceIpAddress()
                "服务器运行中 - $ip:${RemoteMode.DEFAULT_PORT}"
            }
            RemoteMode.isController() -> {
                if (RemoteClient.isConnected) "已连接到 ${RemoteMode.serverHost}" else "未连接"
            }
            else -> "未启用"
        }

        // Show/hide IP input based on mode
        findPreference<EditTextPreference>("remote_server_ip")?.isVisible = RemoteMode.isController()
        findPreference<Preference>("remote_reconnect_now")?.isVisible = RemoteMode.isController()
    }

    override fun onResume() {
        super.onResume()

        // Update notification access status
        findPreference<Preference>("notification_access")?.apply {
            val isEnabled = MediaMonitorService.isEnabled(requireContext())
            summary = if (isEnabled) "已授权" else "未授权 - 点击授权"
        }

        // Update accessibility service status
        val a11yEnabled = PlayerAccessibilityService.isEnabled(requireContext())
        val a11yRunning = PlayerAccessibilityService.getInstance() != null
        findPreference<Preference>("accessibility_access")?.apply {
            summary = when {
                a11yEnabled && a11yRunning -> "已授权且运行中"
                a11yEnabled && !a11yRunning -> "已授权但未运行 - 请点击重新启用"
                else -> "未授权 - 点击授权（QQ音乐需要）"
            }
        }

        // Grey out the auto-open switch when the accessibility service is not running
        findPreference<SwitchPreferenceCompat>("qqmusic_auto_open_player")?.isEnabled = a11yEnabled && a11yRunning

        // Update floating window status
        findPreference<SwitchPreferenceCompat>("floating_window")?.apply {
            if (!Settings.canDrawOverlays(requireContext())) {
                isChecked = false
            }
        }

        // Update write settings status
        findPreference<Preference>("write_settings_access")?.apply {
            val canWrite = Settings.System.canWrite(requireContext())
            summary = if (canWrite) "已授权" else "未授权 - 点击授权"
        }

        // Update usage stats permission status
        findPreference<Preference>("usage_stats_access")?.apply {
            val isGranted = hasUsageStatsPermission()
            summary = if (isGranted) "已授权" else "未授权 - 点击授权（浮窗双击导航需要）"
        }

        // Update remote control status
        updateRemoteStatus()
        val currentMode = when (RemoteMode.currentMode) {
            RemoteMode.AppMode.PLAYER -> "player"
            RemoteMode.AppMode.CONTROLLER -> "controller"
            else -> "standalone"
        }
        updateRemoteModeSummary(currentMode)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        RemoteClient.removeConnectionListener(connectionListener)
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = requireContext().getSystemService(android.content.Context.APP_OPS_SERVICE) as? android.app.AppOpsManager
            ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                requireContext().packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                requireContext().packageName
            )
        }
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }
}
