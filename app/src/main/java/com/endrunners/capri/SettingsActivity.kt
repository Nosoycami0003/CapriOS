package com.endrunners.capri

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var grupoVision: RadioGroup
    private lateinit var grupoTexto: RadioGroup
    private lateinit var campoApiKey: EditText
    private lateinit var campoModelo: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "Elegí tu IA para Capri"
            textSize = 20f
            setPadding(0, 0, 0, 24)
        })

        root.addView(TextView(this).apply {
            text = "Pueden VER tu pantalla (mandan una captura junto a tu pregunta):"
            setPadding(0, 16, 0, 8)
        })

        grupoVision = RadioGroup(this)
        AIProvider.entries.filter { it.supportsVision }.forEach { p ->
            grupoVision.addView(RadioButton(this).apply { text = p.displayName; tag = p.name })
        }
        root.addView(grupoVision)

        root.addView(TextView(this).apply {
            text = "Solo leen texto (no ven la pantalla):"
            setPadding(0, 32, 0, 8)
        })

        grupoTexto = RadioGroup(this)
        AIProvider.entries.filter { !it.supportsVision }.forEach { p ->
            grupoTexto.addView(RadioButton(this).apply { text = p.displayName; tag = p.name })
        }
        root.addView(grupoTexto)

        root.addView(TextView(this).apply {
            text = "Tu clave de API (se guarda cifrada solo en este celular):"
            setPadding(0, 32, 0, 8)
        })

        campoApiKey = EditText(this).apply {
            hint = "Pegá tu API key acá"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(campoApiKey)

        root.addView(TextView(this).apply {
            text = "Modelo (opcional, dejalo vacío si no sabés):"
            setPadding(0, 24, 0, 8)
        })

        campoModelo = EditText(this)
        root.addView(campoModelo)

        root.addView(Button(this).apply {
            text = "Permitir que Capri vea mi pantalla"
            setOnClickListener { pedirPermisoPantalla() }
        })

        root.addView(Button(this).apply {
            text = "GUARDAR"
            setOnClickListener { guardarConfiguracion() }
        })

        cargarConfiguracionActual()
    }

    private fun cargarConfiguracionActual() {
        val provider = CapriPrefs.getProvider(this)
        campoApiKey.setText(CapriPrefs.getApiKey(this))
        campoModelo.hint = provider.defaultModel

        for (i in 0 until grupoVision.childCount) {
            val rb = grupoVision.getChildAt(i) as RadioButton
            if (rb.tag == provider.name) rb.isChecked = true
        }
        for (i in 0 until grupoTexto.childCount) {
            val rb = grupoTexto.getChildAt(i) as RadioButton
            if (rb.tag == provider.name) rb.isChecked = true
        }
    }

    private fun pedirPermisoPantalla() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), 9001)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 9001 && resultCode == Activity.RESULT_OK && data != null) {
            ScreenCapture.guardarPermiso(resultCode, data)
            CapriPrefs.saveConfig(this, CapriPrefs.getProvider(this), CapriPrefs.getApiKey(this), CapriPrefs.getModel(this), true)
            Toast.makeText(this, "Listo, Capri ya puede ver tu pantalla.", Toast.LENGTH_SHORT).show()
        } else if (requestCode == 9001) {
            Toast.makeText(this, "No diste el permiso, Capri seguirá sin ver la pantalla.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun guardarConfiguracion() {
        val seleccionVision = (0 until grupoVision.childCount).map { grupoVision.getChildAt(it) as RadioButton }.firstOrNull { it.isChecked }
        val seleccionTexto = (0 until grupoTexto.childCount).map { grupoTexto.getChildAt(it) as RadioButton }.firstOrNull { it.isChecked }
        val tag = seleccionVision?.tag ?: seleccionTexto?.tag
        val provider = AIProvider.fromId(tag as? String)

        val modelo = campoModelo.text.toString().ifBlank { provider.defaultModel }
        val apiKey = campoApiKey.text.toString().trim()

        CapriPrefs.saveConfig(this, provider, apiKey, modelo, CapriPrefs.isVisionEnabled(this))
        Toast.makeText(this, "Configuración guardada.", Toast.LENGTH_SHORT).show()
        finish()
    }
}
