package com.andrerinas.openheadunit.zmd

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings

/**
 * ZMD Car's «طريقة الاتصال» for Android (cable / wireless / both) applied to the engine, so the
 * driver sets it in one place. Wireless = Native Android Auto (wifiConnectionMode 3) over Wi-Fi
 * Direct: the screen makes its own Wi-Fi for the phone, so no hotspot has to be set up by hand.
 */
object ZmdModes {
    const val ACTION_SET_MODES = "com.zmd.auto.SET_MODES"
    const val EXTRA_USB = "usb"
    const val EXTRA_WIFI = "wifi"
    /** On ZMD Car's connect request: the Bluetooth address of the phone to connect to wirelessly. */
    const val EXTRA_MAC = "zmd_mac"

    private const val PREFS = "zmd"
    private const val KEY_TRANSPORT_SET = "wireless-transport-v1"
    /** Settings.nativeApTransport: 0 = Wi-Fi Direct (made by the screen), 1 = the car's own hotspot. */
    private const val WIFI_DIRECT = 0

    fun apply(ctx: Context, usb: Boolean, wifi: Boolean) {
        val s = App.provide(ctx).settings
        val modes = buildSet {
            if (usb) add(Settings.ConnectionMode.USB)
            if (wifi) add(Settings.ConnectionMode.WIFI)
        }.ifEmpty { setOf(Settings.ConnectionMode.USB) }
        val wasWifi = Settings.ConnectionMode.WIFI in s.connectionModes
        if (s.connectionModes != modes) s.connectionModes = modes
        if (wifi) nativeWireless(ctx, s)
        AppLog.i("ZmdModes: connection set to $modes")
        if (wasWifi && !wifi && aapServiceRunning(ctx)) {
            // Wireless switched off while the engine is up: take the wireless side down now.
            runCatching { ctx.startService(Intent(ctx, AapService::class.java).setAction(AapService.ACTION_STOP_WIRELESS)) }
        }
    }

    /** A wireless connect was asked for: make sure wireless is allowed (keeps the cable as it was). */
    fun allowWireless(ctx: Context) {
        val s = App.provide(ctx).settings
        if (Settings.ConnectionMode.WIFI !in s.connectionModes) s.connectionModes = s.connectionModes + Settings.ConnectionMode.WIFI
        nativeWireless(ctx, s)
    }

    private fun nativeWireless(ctx: Context, s: Settings) {
        if (s.wifiConnectionMode != 3) s.wifiConnectionMode = 3
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.getBoolean(KEY_TRANSPORT_SET, false)) {
            // First time only; a hotspot chosen later in the engine's own settings is kept.
            s.nativeApTransport = WIFI_DIRECT
            p.edit().putBoolean(KEY_TRANSPORT_SET, true).apply()
        }
    }

    @Suppress("DEPRECATION") // getRunningServices still lists the caller's own services
    private fun aapServiceRunning(ctx: Context): Boolean = runCatching {
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getRunningServices(50)
            .any { it.service.packageName == ctx.packageName && it.service.className == AapService::class.java.name }
    }.getOrDefault(false)

    /** ZMD Car sends the mode here (only apps with ZMD Car's key may: AUTO_LINK). */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_SET_MODES) return
            apply(context, intent.getBooleanExtra(EXTRA_USB, true), intent.getBooleanExtra(EXTRA_WIFI, false))
        }
    }
}
