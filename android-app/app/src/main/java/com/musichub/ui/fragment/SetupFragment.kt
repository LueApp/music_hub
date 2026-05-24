package com.musichub.ui.fragment

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.musichub.R
import com.musichub.databinding.FragmentSetupBinding
import com.musichub.service.MediaMonitorService
import com.musichub.service.PlayerAccessibilityService

class SetupFragment : Fragment() {

    private var _binding: FragmentSetupBinding? = null
    private val binding get() = _binding!!

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { updateStatus() }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSetupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            binding.cardNotification.visibility = View.GONE
        }

        binding.btnGrantNotification.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        binding.btnGrantOverlay.setOnClickListener {
            showPermissionGuideDialog(
                R.string.overlay_guide_title_cn,
                R.drawable.guide_overlay,
                R.string.overlay_guide_image_desc_cn,
            ) {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${requireContext().packageName}"))
                )
            }
        }

        binding.btnGrantListener.setOnClickListener {
            showPermissionGuideDialog(
                R.string.notification_listener_guide_title_cn,
                R.drawable.guide_notification_listener,
                R.string.notification_listener_guide_image_desc_cn,
            ) {
                MediaMonitorService.openSettings(requireContext())
            }
        }

        binding.btnGrantWriteSettings.setOnClickListener {
            showPermissionGuideDialog(
                R.string.write_settings_guide_title_cn,
                R.drawable.guide_write_settings,
                R.string.write_settings_guide_image_desc_cn,
            ) {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:${requireContext().packageName}"))
                )
            }
        }

        binding.btnGrantUsageStats.setOnClickListener {
            showPermissionGuideDialog(
                R.string.usage_stats_guide_title_cn,
                R.drawable.guide_usage_stats,
                R.string.usage_stats_guide_image_desc_cn,
            ) {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }

        binding.btnGrantAccessibility.setOnClickListener {
            showPermissionGuideDialog(
                R.string.accessibility_guide_title_cn,
                R.drawable.guide_accessibility,
                R.string.accessibility_guide_image_desc_cn,
            ) {
                PlayerAccessibilityService.openSettings(requireContext())
            }
        }

        binding.btnDone.setOnClickListener {
            requireContext().getSharedPreferences("musichub_prefs", 0)
                .edit().putBoolean("setup_complete", true).apply()
            findNavController().popBackStack()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val ctx = requireContext()

        val notifGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        setGranted(binding.btnGrantNotification, notifGranted)

        setGranted(binding.btnGrantOverlay, Settings.canDrawOverlays(ctx))

        setGranted(binding.btnGrantListener, MediaMonitorService.isEnabled(ctx))

        setGranted(binding.btnGrantWriteSettings, Settings.System.canWrite(ctx))

        setGranted(binding.btnGrantUsageStats, hasUsageStatsPermission(ctx))

        setGranted(binding.btnGrantAccessibility, PlayerAccessibilityService.isEnabled(ctx))
    }

    private fun hasUsageStatsPermission(ctx: Context): Boolean {
        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun setGranted(button: MaterialButton, granted: Boolean) {
        button.text = if (granted) "✓ 已授权" else "授权"
        button.isEnabled = !granted
    }

    private fun showPermissionGuideDialog(
        titleResId: Int,
        imageResId: Int,
        contentDescResId: Int,
        onProceed: () -> Unit,
    ) {
        val ctx = requireContext()
        val view = layoutInflater.inflate(R.layout.dialog_permission_guide, null)
        view.findViewById<android.widget.ImageView>(R.id.guideImage).apply {
            setImageResource(imageResId)
            contentDescription = getString(contentDescResId)
        }
        AlertDialog.Builder(ctx)
            .setTitle(titleResId)
            .setView(view)
            .setPositiveButton(R.string.wakepath_guide_go_settings_cn) { _, _ -> onProceed() }
            .setNegativeButton(R.string.wakepath_guide_cancel_cn, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
