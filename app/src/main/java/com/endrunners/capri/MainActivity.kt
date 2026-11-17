package com.endrunners.capri

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val btnLaunch = Button(this).apply {
            text = "ACTIVAR CAPRI OS"
            setOnClickListener { checkAndStartCapri() }
        }

        val btnConfig = Button(this).apply {
            text = "CONFIGURAR IA"
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }

        layout.addView(btnLaunch)
        layout.addView(btnConfig)
        setContentView(layout)
    }

    private fun checkAndStartCapri() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)

        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } else {
            startForegroundService(Intent(this, CapriService::class.java))
            finish()
        }
    }
}
