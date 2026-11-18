package com.andrerinas.openheadunit.zmd

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Asks for the microphone (Google Assistant / voice inside Android Auto). ZMD Auto has no setup
 * screens, so ZMD Car opens this invisible screen when the driver taps «اسمح» on its notice. If the
 * permission was refused for good, it opens ZMD Auto's app page in Settings instead. Only ZMD Car can
 * open it (see the manifest).
 */
class ZmdPermissionActivity : Activity() {
    private val wanted = arrayOf(Manifest.permission.RECORD_AUDIO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            finish()
            return
        }
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            finish()
            return
        }
        if (savedInstanceState == null) requestPermissions(missing.toTypedArray(), REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        AppLog.i("ZMD: microphone ${if (granted) "allowed" else "refused"}")
        if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            permissions.any { !shouldShowRequestPermissionRationale(it) }
        ) {
            // Refused for good earlier: Android shows no prompt any more, only Settings can change it.
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        finish()
    }

    private companion object {
        const val REQUEST = 7
    }
}
