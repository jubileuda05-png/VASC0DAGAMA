package dev.venice.sniper

import android.app.Activity
import android.app.Application
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { File(filesDir, "crash.txt").writeText(Log.getStackTraceString(e)) }
            prev?.uncaughtException(t, e)
        }
    }
}

class SniperService : Service() {
    override fun onBind(i: Intent?): IBinder? = null
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) {}
}

class MainActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val texto = StringBuilder()
        texto.append("APP OK\n")
        texto.append("Android ").append(Build.VERSION.RELEASE)
        texto.append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        texto.append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n")
        texto.append("ABI: ").append(Build.SUPPORTED_ABIS.joinToString()).append("\n\n")

        runCatching {
            val f = File(filesDir, "crash.txt")
            if (f.exists()) {
                texto.append("ULTIMO ERRO:\n").append(f.readText())
                f.delete()
            }
        }

        val tv = TextView(this)
        tv.text = texto.toString()
        tv.setTextSize(13f)

        val scroll = ScrollView(this)
        scroll.addView(tv)
        setContentView(scroll)
    }
}
