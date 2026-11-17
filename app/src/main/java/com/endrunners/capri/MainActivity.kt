package com.endrunners.capri

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    // El servicio con tipo "microphone" necesita RECORD_AUDIO YA concedido al arrancar
    // (Android 14+), por eso se espera el resultado antes de iniciarlo.
    private val permisosLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (concedido(Manifest.permission.RECORD_AUDIO)) {
                continuarTrasPermisos()
            } else {
                Toast.makeText(this, "Sin micrófono Capri no puede escucharte.", Toast.LENGTH_LONG).show()
            }
        }

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

    private fun concedido(permiso: String) =
        ContextCompat.checkSelfPermission(this, permiso) == PackageManager.PERMISSION_GRANTED

    private fun checkAndStartCapri() {
        val faltantes = mutableListOf<String>()
        if (!concedido(Manifest.permission.RECORD_AUDIO)) faltantes += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 && !concedido(Manifest.permission.POST_NOTIFICATIONS)) {
            faltantes += Manifest.permission.POST_NOTIFICATIONS
        }

        if (faltantes.isNotEmpty()) {
            permisosLauncher.launch(faltantes.toTypedArray())
        } else {
            continuarTrasPermisos()
        }
    }

    private fun continuarTrasPermisos() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Activá \"Mostrar sobre otras apps\" y volvé a tocar ACTIVAR.", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        } else {
            startForegroundService(Intent(this, CapriService::class.java))
            finish()
        }
    }
}
