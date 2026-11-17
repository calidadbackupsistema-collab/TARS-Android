package com.rocaprimaria.tars

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONObject

/** Mediciones para validar TARS en el teléfono: batería, memoria, escucha y modelo local. */
object Diagnostics {
    fun battery(ctx: Context): Int {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun charging(ctx: Context): Boolean {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
    }

    fun json(ctx: Context): String {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val listenMin = if (ListenService.running) (SystemClock.elapsedRealtime() - ListenService.startedAt) / 60000.0 else 0.0
        val batNow = battery(ctx)
        val drop = if (ListenService.running && ListenService.batteryAtStart > 0) ListenService.batteryAtStart - batNow else 0
        val perHour = if (listenMin >= 10) drop / (listenMin / 60.0) else -1.0
        return JSONObject()
            .put("device", Build.MANUFACTURER + " " + Build.MODEL)
            .put("android", Build.VERSION.RELEASE)
            .put("app", BuildConfigInfo.version(ctx))
            .put("battery", batNow)
            .put("charging", charging(ctx))
            .put("batteryOptimized", !pm.isIgnoringBatteryOptimizations(ctx.packageName))
            .put("listening", ListenService.running)
            .put("listenMinutes", Math.round(listenMin * 10) / 10.0)
            .put("batteryDrop", drop)
            .put("batteryPerHour", Math.round(perHour * 10) / 10.0)
            .put("screenOffMinutes", Math.round(ListenService.screenOffMs / 6000.0) / 10.0)
            .put("wakeDetections", ListenService.wakeCount)
            .put("commandsHeard", ListenService.commandCount)
            .put("voskReady", ListenService.modelReady)
            .put("ramAvailMB", mi.availMem / 1_048_576)
            .put("ramTotalMB", mi.totalMem / 1_048_576)
            .put("lowMemory", mi.lowMemory)
            .put("appPssMB", Debug.getPss() / 1024)
            .put("webCacheMB", WebCache.sizeMB(ctx))
            .put("llm", JSONObject(LocalBrain.statusJson(ctx)))
            .toString()
    }
}
