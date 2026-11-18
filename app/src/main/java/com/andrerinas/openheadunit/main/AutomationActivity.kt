package com.andrerinas.openheadunit.main

import android.os.Bundle
import android.content.Intent
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.aap.AapProjectionActivity
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.zmd.ZmdLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * A transparent activity that handles App Shortcuts and Deep Links.
 * It translates incoming intents into service actions for AapService.
 */
class AutomationActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Invisible activity
        window.setBackgroundDrawableResource(android.R.color.transparent)

        val data = intent.data
        val action = intent.action
        
        AppLog.i("AutomationActivity: Received intent. Action: $action, Data: $data")
        
        if (data?.scheme == "headunit") {
            handleUri(data)
        } else {
            val state = intent.getStringExtra("state")
            handleAction(action, state)
        }
        
        finish()
    }

    private fun handleUri(data: android.net.Uri) {
        when (data.host) {
            "connect" -> {
                val ip = data.getQueryParameter("ip")
                if (!ip.isNullOrEmpty()) {
                    ContextCompat.startForegroundService(this, Intent(this, AapService::class.java).apply {
                        action = AapService.ACTION_CONNECT_SOCKET
                    })
                    lifecycleScope.launch(Dispatchers.IO) { App.provide(this@AutomationActivity).commManager.connect(ip, 5277) }
                } else {
                    val autoIntent = Intent(this, AapService::class.java).apply {
                        this.action = AapService.ACTION_CHECK_USB
                    }
                    ContextCompat.startForegroundService(this, autoIntent)
                }
            }
            "disconnect" -> {
                val stopIntent = Intent(this, AapService::class.java).apply {
                    this.action = AapService.ACTION_DISCONNECT
                }
                ContextCompat.startForegroundService(this, stopIntent)
            }
            "exit" -> {
                val exitIntent = Intent(this, AapService::class.java).apply {
                    this.action = AapService.ACTION_STOP_SERVICE
                }
                ContextCompat.startForegroundService(this, exitIntent)
                // Broadcast a finish request to close MainActivity if it's open
                sendBroadcast(Intent("com.andrerinas.openheadunit.ACTION_FINISH_ACTIVITIES"))
            }
            "nightmode" -> {
                val state = data.getQueryParameter("state")
                applyNightMode(state)
            }
        }
    }

    private fun handleAction(incomingAction: String?, incomingState: String?) {
        when (incomingAction) {
            "com.andrerinas.openheadunit.ACTION_SET_NIGHT_MODE" -> applyNightMode(incomingState)
            "com.andrerinas.openheadunit.ACTION_CONNECT" -> {
                // ZMD: ZMD Car's Android Auto icon. If a session is already up, just bring its
                // screen back (this screen is in front, so Android allows it); otherwise connect in
                // the background and let ZMD Car show the stages.
                val state = App.provide(this).commManager.connectionState.value
                when (state) {
                    is CommManager.ConnectionState.HandshakeComplete,
                    is CommManager.ConnectionState.TransportStarted -> {
                        try {
                            startActivity(AapProjectionActivity.intent(this).apply {
                                putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
                                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                            })
                        } catch (e: Exception) {
                            AppLog.w("AutomationActivity: Could not bring Android Auto back: ${e.message}")
                        }
                        ZmdLink.resend(this)
                        return
                    }
                    is CommManager.ConnectionState.Connecting,
                    is CommManager.ConnectionState.Connected,
                    is CommManager.ConnectionState.StartingTransport -> {
                        ZmdLink.resend(this) // already on its way
                        return
                    }
                    else -> {}
                }
                // ZMD: ZMD Car saw this phone connect over Bluetooth (by its address) and the driver
                // allows wireless: connect over Wi-Fi straight away, no picking the device and no
                // «connect» button (what the settings screen's device list does by hand).
                val mac = intent.getStringExtra(com.andrerinas.openheadunit.zmd.ZmdModes.EXTRA_MAC)
                if (!mac.isNullOrEmpty()) {
                    com.andrerinas.openheadunit.zmd.ZmdModes.allowWireless(this)
                    ZmdLink.send(this, ZmdLink.WIRELESS)
                    ContextCompat.startForegroundService(this, Intent(this, AapService::class.java).apply {
                        this.action = AapService.ACTION_NATIVE_AA_POKE
                        putExtra(AapService.EXTRA_MAC, mac)
                    })
                    return
                }
                if (ZmdLink.switching()) {
                    ZmdLink.resend(this) // the phone is switching into Android Auto mode right now
                    return
                }
                ZmdLink.send(this, ZmdLink.START)
                val autoIntent = Intent(this, AapService::class.java).apply {
                    this.action = AapService.ACTION_CHECK_USB
                }
                ContextCompat.startForegroundService(this, autoIntent)
            }
            "com.andrerinas.openheadunit.ACTION_DISCONNECT" -> {
                val stopIntent = Intent(this, AapService::class.java).apply {
                    this.action = AapService.ACTION_DISCONNECT
                }
                ContextCompat.startForegroundService(this, stopIntent)
            }
            "com.andrerinas.openheadunit.ACTION_START_SELF_MODE" -> {
                val selfIntent = Intent(this, AapService::class.java).apply {
                    this.action = AapService.ACTION_START_SELF_MODE
                }
                ContextCompat.startForegroundService(this, selfIntent)

                // [FIX] Launch AapProjectionActivity NOW, while AutomationActivity is
                // still in the foreground. This is critical on Android 10+ where
                // background activity launches are silently blocked: by the time
                // AapService finishes the Self Mode handshake and calls
                // launchAapProjectionActivity(), the app has no foreground window and
                // the launch often fails, leaving the user at the launcher.
                //
                // Starting AapProjectionActivity from this foreground context is always
                // allowed. Its loading overlay will display while Self Mode negotiates;
                // once the handshake completes AapService will reorder it to front
                // (it's already there) and start streaming.
                try {
                    startActivity(
                        AapProjectionActivity.intent(this).apply {
                            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        }
                    )
                } catch (e: Exception) {
                    AppLog.w("AutomationActivity: Could not pre-launch AapProjectionActivity: ${e.message}")
                }
            }
            "com.andrerinas.openheadunit.ACTION_STOP_SERVICE",
            "com.andrerinas.openheadunit.ACTION_EXIT" -> {
                val exitIntent = Intent(this, AapService::class.java).apply {
                    this.action = AapService.ACTION_STOP_SERVICE
                }
                ContextCompat.startForegroundService(this, exitIntent)
                sendBroadcast(Intent("com.andrerinas.openheadunit.ACTION_FINISH_ACTIVITIES").apply {
                    setPackage(packageName)
                })
            }
        }
    }

    private fun applyNightMode(state: String?) {
        val appSettings = App.provide(this).settings
        when (state?.lowercase()) {
            "day" -> appSettings.nightMode = Settings.NightMode.DAY
            "night" -> appSettings.nightMode = Settings.NightMode.NIGHT
            "auto" -> appSettings.nightMode = Settings.NightMode.AUTO
        }
        val updateIntent = Intent(this, AapService::class.java).apply {
            this.action = AapService.ACTION_REQUEST_NIGHT_MODE_UPDATE
        }
        ContextCompat.startForegroundService(this, updateIntent)
    }
}
