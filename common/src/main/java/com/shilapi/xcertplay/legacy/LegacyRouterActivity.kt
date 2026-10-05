package com.shilapi.xcertplay.legacy

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle

/**
 * Single launcher entry for the compat-4.4 build: modern units (API 23+, where Compose runs)
 * get the upstream UI, older units get the View-based legacy home. Keeping one launcher means
 * one APK serves both generations.
 */
class LegacyRouterActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = if (Build.VERSION.SDK_INT >= 23) {
            Intent(this, com.shilapi.xcertplay.DiPlayActivity::class.java)
        } else {
            Intent(this, LegacyHomeActivity::class.java)
        }
        startActivity(target)
        finish()
    }
}
