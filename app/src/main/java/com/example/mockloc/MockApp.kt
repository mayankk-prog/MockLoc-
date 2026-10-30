package com.example.mockloc

import android.app.Application
import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter

/** Saves the last crash so the app can show it on the next launch (no computer needed). */
class MockApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                getSharedPreferences("mockloc", Context.MODE_PRIVATE).edit()
                    .putString("lastCrash", "Thread: ${thread.name}\n$sw".take(6000))
                    .commit()
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
