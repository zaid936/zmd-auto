package com.andrerinas.openheadunit.zmd

import android.content.Context
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings

/**
 * ZMD Auto's settings on its first start. It has no setup screens of its own (it runs behind ZMD
 * Car), so the settings it needs are set here, once; anything the user changes later in ZMD Auto's
 * own settings (opened from ZMD Car) is left alone.
 */
object ZmdDefaults {
    private const val PREFS = "zmd"
    private const val KEY_APPLIED = "defaults-v1"

    fun applyOnce(ctx: Context, s: Settings) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getBoolean(KEY_APPLIED, false)) return
        // Any phone on the cable is switched into Android Auto mode, not only Google-made ones.
        s.autoStartOnUsb = true
        s.autoConnectSingleUsbDevice = true
        // ZMD Car decides what opens when the screen starts; ZMD Auto doesn't start on its own.
        s.autoStartOnBoot = false
        s.reopenOnReconnection = false
        // Cable only: no Wi-Fi, hotspot or Bluetooth work in the background unless the user turns
        // wireless on in ZMD Auto's settings.
        s.connectionModes = setOf(Settings.ConnectionMode.USB)
        s.primaryConnection = Settings.ConnectionKind.USB_CABLE
        s.showToastMessages = false
        p.edit().putBoolean(KEY_APPLIED, true).apply()
        AppLog.i("ZMD: first start, ZMD Auto defaults applied (cable, auto-connect, no own screens)")
    }

    /** The car's name the phone shows (Android Auto's welcome screen and connection history). Fixed. */
    const val CAR_NAME = "ZMD"

    /** Settings that always hold, every start (the user asked for the car name to stay «ZMD»). */
    fun enforce(s: Settings) {
        if (s.vehicleDisplayName != CAR_NAME) s.vehicleDisplayName = CAR_NAME
    }
}
