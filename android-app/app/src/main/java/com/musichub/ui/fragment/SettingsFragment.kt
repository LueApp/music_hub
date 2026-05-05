package com.musichub.ui.fragment

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.musichub.MusicHubApplication
import com.musichub.R
import com.musichub.service.DeepLinkLauncher
import com.musichub.service.FloatingWindowService
import com.musichub.service.MediaMonitorService
import com.musichub.service.PlayerAccessibilityService
import kotlinx.coroutines.launch

class SettingsFragment : PreferenceFragmentCompat() {

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
    }
}
