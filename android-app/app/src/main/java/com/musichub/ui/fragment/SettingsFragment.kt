package com.musichub.ui.fragment

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.musichub.MusicHubApplication
import com.musichub.R
import com.musichub.remote.RemoteClient
import com.musichub.remote.RemoteMode
import com.musichub.remote.RemoteServerService
import com.musichub.service.FloatingWindowService
import com.musichub.service.MediaMonitorService
import com.musichub.service.PlayerAccessibilityService
import kotlinx.coroutines.launch

class SettingsFragment : PreferenceFragmentCompat() {

    private val connectionListener: (Boolean) -> Unit = { connected ->
        updateRemoteStatus()
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

        // Add connection listener
        RemoteClient.addConnectionListener(connectionListener)
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
    }

    override fun onResume() {
        super.onResume()

        // Update notification access status
        findPreference<Preference>("notification_access")?.apply {
            val isEnabled = MediaMonitorService.isEnabled(requireContext())
            summary = if (isEnabled) "已授权" else "未授权 - 点击授权"
        }

        // Update accessibility service status
        findPreference<Preference>("accessibility_access")?.apply {
            val isEnabled = PlayerAccessibilityService.isEnabled(requireContext())
            val isRunning = PlayerAccessibilityService.getInstance() != null
            summary = when {
                isEnabled && isRunning -> "已授权且运行中"
                isEnabled && !isRunning -> "已授权但未运行 - 请点击重新启用"
                else -> "未授权 - 点击授权（QQ音乐需要）"
            }
        }

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
}
