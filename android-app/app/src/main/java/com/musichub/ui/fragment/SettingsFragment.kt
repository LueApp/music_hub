package com.musichub.ui.fragment

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.musichub.R
import com.musichub.service.DeepLinkLauncher
import com.musichub.service.FloatingWindowService
import com.musichub.service.MediaMonitorService
import com.musichub.service.ScreenRotationHelper

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

        // Playback mode preference
        findPreference<ListPreference>("playback_mode")?.apply {
            // Set initial value from DeepLinkLauncher
            value = when (DeepLinkLauncher.playbackMode) {
                DeepLinkLauncher.PlaybackMode.FOREGROUND -> "foreground"
                else -> "background"
            }
            updatePlaybackModeSummary(this, value)

            setOnPreferenceChangeListener { _, newValue ->
                DeepLinkLauncher.playbackMode = when (newValue) {
                    "foreground" -> DeepLinkLauncher.PlaybackMode.FOREGROUND
                    else -> DeepLinkLauncher.PlaybackMode.BACKGROUND
                }
                // Use newValue since pref.value hasn't been updated yet
                updatePlaybackModeSummary(this, newValue as String)
                true
            }
        }

        // Auto-landscape preference
        findPreference<SwitchPreferenceCompat>("auto_landscape")?.apply {
            isChecked = DeepLinkLauncher.autoLandscape

            setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                if (enabled && !ScreenRotationHelper.canWriteSettings(requireContext())) {
                    // Request WRITE_SETTINGS permission
                    ScreenRotationHelper.requestWriteSettingsPermission(requireContext())
                    false // Don't toggle yet, wait for permission
                } else {
                    DeepLinkLauncher.autoLandscape = enabled
                    true
                }
            }
        }
    }

    private fun updatePlaybackModeSummary(pref: ListPreference, currentValue: String) {
        pref.summary = when (currentValue) {
            "foreground" -> "前台模式：切换歌曲时跳转到音乐应用"
            else -> "后台模式：切换歌曲时保持在当前应用"
        }
    }

    override fun onResume() {
        super.onResume()

        // Update notification access status
        findPreference<Preference>("notification_access")?.apply {
            val isEnabled = MediaMonitorService.isEnabled(requireContext())
            summary = if (isEnabled) "已授权" else "未授权 - 点击授权"
        }

        // Update floating window status
        findPreference<SwitchPreferenceCompat>("floating_window")?.apply {
            if (!Settings.canDrawOverlays(requireContext())) {
                isChecked = false
            }
        }

        // Sync playback mode state
        findPreference<ListPreference>("playback_mode")?.apply {
            value = when (DeepLinkLauncher.playbackMode) {
                DeepLinkLauncher.PlaybackMode.FOREGROUND -> "foreground"
                else -> "background"
            }
            updatePlaybackModeSummary(this, value)
        }

        // Sync auto-landscape state (may have gotten permission while away)
        findPreference<SwitchPreferenceCompat>("auto_landscape")?.apply {
            isChecked = DeepLinkLauncher.autoLandscape
        }
    }
}
