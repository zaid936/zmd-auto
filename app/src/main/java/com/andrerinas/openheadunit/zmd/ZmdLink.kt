package com.andrerinas.openheadunit.zmd

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.utils.AppLog

/**
 * ZMD Auto -> ZMD Car.
 *
 * ZMD Auto has no screens or messages of its own: ZMD Car (the hub) shows the driver every stage of
 * an Android Auto connection and, when something goes wrong, what went wrong. This is the one place
 * that tells ZMD Car what is happening. Messages go to ZMD Car's package only, and ZMD Car accepts
 * them only from an app holding [PERMISSION] (a signature permission: signed with the ZMD key).
 *
 * A cable connection goes: START -> (PERMISSION) -> SWITCHING -> (PERMISSION) -> CONNECTING ->
 * HANDSHAKE -> READY -> RUNNING -> ENDED, or stops with FAILED and a reason.
 */
object ZmdLink {
    const val HUB_PACKAGE = "com.zmd.carhub"
    const val PERMISSION = "com.zmd.carhub.permission.AUTO_LINK"
    const val ACTION_STATUS = "com.zmd.auto.STATUS"

    const val EXTRA_STATE = "state"
    const val EXTRA_REASON = "reason"
    const val EXTRA_DETAIL = "detail"
    /** With READY: true = ZMD Auto put Android Auto's screen up itself; false = ZMD Car must. */
    const val EXTRA_SHOWN = "shown"

    // ---- states
    /** ZMD Car asked to connect; looking for the phone on the cable. */
    const val START = "start"
    /** ZMD Car asked to connect WIRELESSLY to the phone it saw on Bluetooth; waiting for it on Wi-Fi. */
    const val WIRELESS = "wireless"
    /** Self mode: Android Auto from the Android Auto app on this screen itself (no phone). */
    const val SELF = "self"
    /** Android's «allow USB» prompt is up for ZMD Auto; the user has to answer it. */
    const val PERMISSION_ASK = "permission"
    /** Asking the phone to switch into Android Auto mode (it drops off the cable and comes back). */
    const val SWITCHING = "switching"
    /** The phone is in Android Auto mode; opening the link to it. */
    const val CONNECTING = "connecting"
    /** Link open; the phone and the screen are agreeing on encryption. */
    const val HANDSHAKE = "handshake"
    /** The phone accepted: Android Auto's picture is coming up now. */
    const val READY = "ready"
    /** Android Auto is live. */
    const val RUNNING = "running"
    /** A live session is over (reason: [USER_EXIT], [PHONE_ENDED] or [LOST]). */
    const val ENDED = "ended"
    /** It did not come up; the reason says why. */
    const val FAILED = "failed"

    // ---- reasons
    const val NO_PHONE = "no_phone"
    const val PERMISSION_DENIED = "permission_denied"
    const val PERMISSION_ERROR = "permission_error"
    const val SWITCH_FAILED = "switch_failed"
    const val NO_RESPONSE = "no_response"
    const val CONNECT_FAILED = "connect_failed"
    const val SILENT = "silent"
    const val HANDSHAKE_FAILED = "handshake"
    const val USER_EXIT = "user_exit"
    const val PHONE_ENDED = "phone"
    const val LOST = "lost"
    const val OTHER = "other"
    /** Wireless: the phone never came over Wi-Fi (its Android Auto wireless off, Wi-Fi off, too far...). */
    const val WIRELESS_TIMEOUT = "wireless_timeout"
    /** Self mode: the Android Auto app's «head unit server» isn't running (it must be started in its developer settings). */
    const val SELF_NO_SERVER = "self_no_server"

    /** How long the phone may take to come back in Android Auto mode after the switch request. */
    private const val SWITCH_TIMEOUT_MS = 12_000L
    /** How long a connect request may sit without anything happening. */
    private const val START_TIMEOUT_MS = 8_000L
    /** Wireless takes longer: Bluetooth hand-off, then the phone joins our Wi-Fi. */
    private const val WIRELESS_TIMEOUT_MS = 45_000L

    private val main = Handler(Looper.getMainLooper())

    /** The last state sent (one of the constants above), "" before the first. */
    @Volatile var phase: String = ""
        private set
    @Volatile private var phaseAt = 0L
    /** The current session got as far as READY. */
    @Volatile private var live = false
    @Volatile private var lastFailAt = 0L
    @Volatile private var lastSent = ""
    @Volatile private var lastSentAt = 0L

    fun send(ctx: Context, state: String, reason: String? = null, detail: String? = null, shown: Boolean? = null) {
        val app = ctx.applicationContext ?: ctx
        if (state == READY || state == RUNNING) live = true
        if (state == FAILED) lastFailAt = SystemClock.elapsedRealtime()
        val now = SystemClock.elapsedRealtime()
        if (state != phase) phaseAt = now
        phase = state
        val key = "$state|$reason|$detail|$shown"
        if (key == lastSent && now - lastSentAt < 1500) return // the same news twice in a row
        lastSent = key
        lastSentAt = now
        AppLog.i("ZmdLink: $state${reason?.let { " ($it)" } ?: ""}${detail?.let { ": $it" } ?: ""}")
        val i = Intent(ACTION_STATUS).setPackage(HUB_PACKAGE).putExtra(EXTRA_STATE, state)
        if (reason != null) i.putExtra(EXTRA_REASON, reason)
        if (detail != null) i.putExtra(EXTRA_DETAIL, detail.take(300))
        if (shown != null) i.putExtra(EXTRA_SHOWN, shown)
        runCatching { app.sendBroadcast(i, PERMISSION) }
            .onFailure { AppLog.w("ZmdLink: could not tell ZMD Car: ${it.message}") }
        armWatchdog(app, state)
    }

    fun fail(ctx: Context, reason: String, detail: String? = null) = send(ctx, FAILED, reason, detail)

    /** ZMD Car is installed (it puts Android Auto's screen up when ZMD Auto may not). */
    fun hubInstalled(ctx: Context): Boolean =
        runCatching { ctx.packageManager.getApplicationInfo(HUB_PACKAGE, 0); true }.getOrDefault(false)

    /**
     * Brings ZMD Car's map to the front (Back inside Android Auto). Only from a screen of ours that
     * is in front, which Android always allows. false = ZMD Car isn't there.
     */
    fun openHubMap(ctx: Context): Boolean {
        val i = Intent().setClassName(HUB_PACKAGE, "$HUB_PACKAGE.MapActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(i); true }
            .onFailure { AppLog.w("ZmdLink: could not open ZMD Car's map: ${it.message}") }
            .getOrDefault(false)
    }

    /** Every change of [CommManager.connectionState], reported by CommManager itself as it happens. */
    fun onConnectionState(ctx: Context, s: CommManager.ConnectionState) {
        when (s) {
            is CommManager.ConnectionState.Connecting -> send(ctx, CONNECTING)
            is CommManager.ConnectionState.Connected -> send(ctx, HANDSHAKE)
            // READY is sent by AapService when it brings the picture up (it knows whether it could).
            is CommManager.ConnectionState.StartingTransport,
            is CommManager.ConnectionState.HandshakeComplete -> {}
            is CommManager.ConnectionState.TransportStarted -> send(ctx, RUNNING)
            is CommManager.ConnectionState.Error -> {
                if (live) {
                    // Android Auto was already on screen: the session broke, it didn't fail to start.
                    live = false
                    send(ctx, ENDED, LOST, s.message)
                } else {
                    fail(ctx, reasonOf(s.message), s.message)
                }
            }
            is CommManager.ConnectionState.Disconnected -> {
                val wasLive = live
                live = false
                val justFailed = SystemClock.elapsedRealtime() - lastFailAt < 3000
                when {
                    wasLive -> send(
                        ctx, ENDED,
                        when {
                            s.isUserExit -> USER_EXIT
                            s.isClean -> PHONE_ENDED
                            else -> LOST
                        }
                    )
                    justFailed -> {} // the failure itself was already reported
                    phase == CONNECTING || phase == HANDSHAKE ->
                        fail(ctx, CONNECT_FAILED, "the link closed before the phone answered")
                    else -> {}
                }
            }
        }
    }

    /** The phone was asked to switch into Android Auto mode moments ago and hasn't come back yet. */
    fun switching(): Boolean =
        phase == SWITCHING && SystemClock.elapsedRealtime() - phaseAt < SWITCH_TIMEOUT_MS

    /** Resends where things stand, e.g. when ZMD Car asks to connect while a connection is under way. */
    fun resend(ctx: Context) {
        val p = phase
        if (p.isEmpty()) return
        lastSent = ""
        send(ctx, p)
    }

    private fun reasonOf(message: String): String = when {
        message == CommManager.ERROR_HANDSHAKE_PEER_SILENT -> SILENT
        message.contains("permission", ignoreCase = true) -> PERMISSION_DENIED
        message.startsWith("Handshake failed") -> HANDSHAKE_FAILED
        message.startsWith("Connection failed") || message.startsWith("Starting handshake") -> CONNECT_FAILED
        else -> OTHER
    }

    /**
     * Two stages can end in silence: the phone is asked to switch into Android Auto mode and never
     * comes back in it, or a connect request finds nothing to act on. Report those instead of
     * leaving ZMD Car waiting.
     */
    private fun armWatchdog(ctx: Context, state: String) {
        main.removeCallbacksAndMessages(WATCHDOG)
        val (timeout, reason, detail) = when (state) {
            SWITCHING -> Triple(SWITCH_TIMEOUT_MS, NO_RESPONSE, "the phone did not come back in Android Auto mode")
            START -> Triple(START_TIMEOUT_MS, NO_PHONE, "nothing to connect to on the cable")
            WIRELESS -> Triple(WIRELESS_TIMEOUT_MS, WIRELESS_TIMEOUT, "the phone did not come over Wi-Fi")
            SELF -> Triple(30_000L, SELF_NO_SERVER, "the Android Auto app on this screen did not answer")
            else -> return
        }
        main.postAtTime({ if (phase == state) fail(ctx, reason, detail) }, WATCHDOG, SystemClock.uptimeMillis() + timeout)
    }

    private val WATCHDOG = Any()
}
