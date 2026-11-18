package com.andrerinas.openheadunit.utils

import android.content.Context
import android.widget.Toast

/**
 * ZMD: ZMD Auto shows no messages of its own. It works behind ZMD Car, which shows the driver every
 * connection stage and the reason for any failure (see zmd.ZmdLink). What used to be a toast is
 * only written to the log now.
 */
object ToastUtils {
    @JvmStatic
    fun showToast(context: Context, text: String, duration: Int = Toast.LENGTH_SHORT, force: Boolean = false) {
        AppLog.i("Toast (not shown, ZMD Car shows the stages): $text")
    }

    @JvmStatic
    fun showToast(context: Context, resId: Int, duration: Int = Toast.LENGTH_SHORT, force: Boolean = false) {
        val text = runCatching { context.getString(resId) }.getOrDefault(resId.toString())
        AppLog.i("Toast (not shown, ZMD Car shows the stages): $text")
    }
}
