package com.hgkim.whisperandroid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.hgkim.whisperandroid.ui.MainScreen
import com.hgkim.whisperandroid.ui.MainViewModel
import com.hgkim.whisperandroid.ui.theme.WhisperAndroidTheme

class MainActivity : ComponentActivity() {

    private val container by lazy { (application as WhisperApp).container }

    private val viewModel: MainViewModel by viewModels { MainViewModel.factory(container) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            // User just granted; proceed with the deferred record action.
            viewModel.onMicTap()
        } else {
            viewModel.onPermissionResult(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WhisperAndroidTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        viewModel = viewModel,
                        onMicTap = ::handleMicTap,
                        onOpenSettings = ::openAppSettings,
                        onRetryDownload = viewModel::onRetryDownload,
                        modifier = Modifier.fillMaxSize().padding(innerPadding),
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // If the user just came back from Settings with permission granted,
        // clear the PermissionDenied error state automatically (per
        // reviewer-3 follow-up #1).
        if (hasRecordPermission()) {
            viewModel.onPermissionGranted()
        }
    }

    private fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

    private fun handleMicTap() {
        if (hasRecordPermission()) {
            viewModel.onMicTap()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }
}
