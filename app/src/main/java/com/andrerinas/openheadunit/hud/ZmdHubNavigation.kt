package com.andrerinas.openheadunit.hud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * ZMD: ZMD Car's OWN map navigation (not Android Auto) on the BYD instrument cluster / HUD, through
 * the same outputs Android Auto's turn-by-turn uses ([BydNavigationOutputs]). ZMD Car sends one
 * broadcast per guidance update (only ZMD Car may: AUTO_LINK); nothing happens on screens that are
 * not BYD ([BydNavigationOutputs.available]).
 *
 * Extras: [EXTRA_TYPE] = the maneuver as Android Auto's Maneuver type number (TYPE_TURN_NORMAL_LEFT
 * = 7, TYPE_STRAIGHT = 36, TYPE_DESTINATION = 39, roundabouts 30–35...), [EXTRA_EXIT] roundabout
 * exit, [EXTRA_DISTANCE] metres to it, [EXTRA_ROAD], [EXTRA_REMAINING] metres and [EXTRA_SECONDS]
 * to the destination. [ACTION_STOP] clears the cluster when the route ends.
 */
class ZmdHubNavigation : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_UPDATE -> {
                if (!BydNavigationOutputs.available(context)) return
                val type = intent.getIntExtra(EXTRA_TYPE, -1)
                val exit = intent.getIntExtra(EXTRA_EXIT, 0)
                val codes = BydNavigationMapper.codes(type, exit) ?: return
                val distance = intent.getIntExtra(EXTRA_DISTANCE, -1).takeIf { it >= 0 } ?: return
                BydNavigationOutputs.start(context)
                BydNavigationOutputs.update(BydGuidance(
                    codes.cluster, codes.gaode, codes.arrow, codes.exit, distance,
                    intent.getStringExtra(EXTRA_ROAD).orEmpty(),
                    intent.getIntExtra(EXTRA_REMAINING, -1),
                    intent.getIntExtra(EXTRA_SECONDS, -1),
                ))
            }
            ACTION_STOP -> {
                BydNavigationOutputs.update(null)
                Log.i(TAG, "ZMD Car's route ended: cluster guidance cleared")
            }
        }
    }

    companion object {
        private const val TAG = "ZmdHubNav"
        const val ACTION_UPDATE = "com.zmd.auto.NAV"
        const val ACTION_STOP = "com.zmd.auto.NAV_STOP"
        const val EXTRA_TYPE = "type"
        const val EXTRA_EXIT = "exit"
        const val EXTRA_DISTANCE = "distance"
        const val EXTRA_ROAD = "road"
        const val EXTRA_REMAINING = "remaining"
        const val EXTRA_SECONDS = "seconds"
    }
}
