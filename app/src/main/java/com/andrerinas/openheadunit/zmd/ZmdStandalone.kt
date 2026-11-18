package com.andrerinas.openheadunit.zmd

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * The SEPARATE ZMD Auto app (com.zmd.auto), for screens without ZMD Car. When ZMD Car is installed
 * with Android Auto built in (the same engine), two apps would answer the phone's cable, so this
 * one steps aside: it stops answering USB and stops starting by itself. Its launcher icon stays,
 * and opening it runs [sync] again, so it comes back by itself once ZMD Car is gone.
 * Inside ZMD Car (engine build) none of this runs.
 */
object ZmdStandalone {
    private const val TAG = "ZmdStandalone"
    const val ACTION_STEP_ASIDE = "com.zmd.auto.STEP_ASIDE"
    private const val ENGINE_SERVICE = "com.andrerinas.openheadunit.aap.AapService"

    /** Components that make this app answer a phone or start by itself. */
    private val AUTO_PARTS = listOf(
        "com.andrerinas.openheadunit.app.UsbAttachedActivity",
        "com.andrerinas.openheadunit.app.BootCompleteReceiver",
        "com.andrerinas.openheadunit.app.AutoStartReceiver",
        "com.andrerinas.openheadunit.app.WifiAutoStartReceiver",
    )

    /** ZMD Car on this screen carries the Android Auto engine itself. */
    fun hubHasEngine(ctx: Context): Boolean = runCatching {
        ctx.packageManager.getServiceInfo(ComponentName(ZmdLink.HUB_PACKAGE, ENGINE_SERVICE), 0)
        true
    }.getOrDefault(false)

    fun sync(ctx: Context) {
        if (ctx.packageName == ZmdLink.HUB_PACKAGE) return // the engine inside ZMD Car
        val aside = hubHasEngine(ctx)
        val pm = ctx.packageManager
        for (cls in AUTO_PARTS) {
            val c = ComponentName(ctx.packageName, cls)
            val want = if (aside) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            if (runCatching { pm.getComponentEnabledSetting(c) }.getOrNull() == want) continue
            runCatching { pm.setComponentEnabledSetting(c, want, PackageManager.DONT_KILL_APP) }
                .onFailure { Log.w(TAG, "can't switch $cls", it) }
        }
        Log.i(TAG, if (aside) "ZMD Car has Android Auto built in: stepping aside" else "standalone: answering the phone")
    }

    /** ZMD Car (with the engine) tells this app to step aside, through the signature permission. */
    class StepAsideReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_STEP_ASIDE) sync(context)
        }
    }
}
