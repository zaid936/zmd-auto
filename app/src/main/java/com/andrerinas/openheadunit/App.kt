package com.andrerinas.openheadunit

import androidx.appcompat.app.AppCompatDelegate
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.UserManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.multidex.MultiDex
import com.andrerinas.openheadunit.main.BackgroundNotification
import com.andrerinas.openheadunit.aap.AapNavigation
import com.andrerinas.openheadunit.ssl.ConscryptInitializer
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.AppThemeManager
import com.andrerinas.openheadunit.utils.Settings
import android.os.SystemClock
import java.io.File

/**
 * The standalone ZMD Auto app. ZMD: the start-up work lives in [init] and the shared parts in
 * [provide], so the same engine also runs inside ZMD Car, where this class is not the
 * Application (there EngineInitProvider calls [init] in ZMD Car's ":auto" process).
 */
class App : Application() {

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        MultiDex.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        init(this)
        // ZMD: the separate app steps aside when ZMD Car carries the same engine.
        com.andrerinas.openheadunit.zmd.ZmdStandalone.sync(this)
    }

    companion object {
        const val defaultChannel = "headunit_service_v2"
        const val bootStartChannel = "headunit_boot_start"
        val appStartTime = SystemClock.elapsedRealtime()
        var appThemeManager: AppThemeManager? = null
        var isPiPActive = false

        @Volatile
        var instance: App? = null
            private set

        @Volatile
        private var component: AppComponent? = null
        private var started = false

        fun get(context: Context): App {
            return context.applicationContext as App
        }

        /** The engine's shared parts, one set per process (works whatever the Application class is). */
        fun provide(context: Context): AppComponent {
            component?.let { return it }
            synchronized(this) {
                component?.let { return it }
                return AppComponent(context.applicationContext).also { component = it }
            }
        }

        /** What the app does at start-up. Runs once per process. */
        fun init(app: Application) {
            synchronized(this) {
                if (started) return
                started = true
            }

            // Enable vector drawable support on older Android versions
            AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)

            if (ConscryptInitializer.isNeededForTls12()) {
                ConscryptInitializer.initialize()
            }

            // Root support
            provide(app).suExecutor.register()

            if (isUserUnlocked(app)) {
                val settings = Settings(app) // Create a Settings instance
                AppLog.init(settings, app) // Initialize AppLog with settings for conditional logging
                // ZMD: settings ZMD Auto needs to work behind ZMD Car (first start only).
                com.andrerinas.openheadunit.zmd.ZmdDefaults.applyOnce(app, settings)
                com.andrerinas.openheadunit.zmd.ZmdDefaults.enforce(settings) // car name «ZMD», always

                // Sync auto-start settings to device-protected storage so that
                // BootCompleteReceiver, UsbAttachedActivity, and AutoStartReceiver
                // can read them during locked boot (before user unlock)
                Settings.syncAutoStartOnBootToDeviceStorage(app, settings.autoStartOnBoot)
                Settings.syncAutoStartOnUsbToDeviceStorage(app, settings.autoStartOnUsb)
                Settings.syncAutoStartOnWifiToDeviceStorage(app, settings.autoStartOnWifi)
                Settings.syncAutoStartWifiSsidToDeviceStorage(app, settings.autoStartWifiSsid)
                Settings.syncAutoStartBtMacsToDeviceStorage(app, settings.autoStartBluetoothDeviceMacs)

                // Apply app theme (runs the live manager when dynamic, or when a saved place
                // can force the app theme even over a static base).
                AppThemeManager.reapply(app, settings)
            } else {
                AppLog.init(null, app) // Initialize with default logging if locked
                AppLog.w("App started in Direct Boot mode (locked). Settings access deferred.")
            }

            if (ConscryptInitializer.isAvailable()) {
                AppLog.i("Conscrypt security provider is active")
            } else if (ConscryptInitializer.isNeededForTls12()) {
                AppLog.w("Conscrypt not available - TLS 1.2 may not work on this device")
            }

            AppLog.d( "native library dir ${app.applicationInfo.nativeLibraryDir}")

            File(app.applicationInfo.nativeLibraryDir).listFiles()?.forEach { file ->
                AppLog.d( "   ${file.name}")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = provide(app).notificationManager
                // ZMD: lowest importance, so the always-there service notice stays out of the way.
                val serviceChannel = NotificationChannel(defaultChannel, "ZMD Auto", NotificationManager.IMPORTANCE_MIN)
                serviceChannel.description = "Persistent service notification"
                serviceChannel.setShowBadge(false)
                nm.createNotificationChannel(serviceChannel)

                val mediaChannel = NotificationChannel(BackgroundNotification.mediaChannel, "Media Playback", NotificationManager.IMPORTANCE_MIN)
                mediaChannel.setSound(null, null)
                mediaChannel.setShowBadge(false)
                nm.createNotificationChannel(mediaChannel)

                AapNavigation.createNotificationChannel(app)

                val bootChannel = NotificationChannel(bootStartChannel, "Boot Auto-Start", NotificationManager.IMPORTANCE_HIGH)
                bootChannel.description = "Shown once after boot to open the app"
                bootChannel.setShowBadge(false)
                nm.createNotificationChannel(bootChannel)
            }

            // Register the main broadcast receiver safely for Android 14+ using ContextCompat
            ContextCompat.registerReceiver(app, AapBroadcastReceiver(), AapBroadcastReceiver.filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }

        private fun isUserUnlocked(ctx: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val userManager = ctx.getSystemService(Context.USER_SERVICE) as UserManager
                userManager.isUserUnlocked
            } else {
                true
            }
        }
    }
}
