package com.endrunners.capri

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    // UN solo grupo: con dos grupos separados se podían marcar dos IA a la vez
    // y siempre ganaba la del primer grupo.
    private lateinit var grupoProveedor: RadioGroup
    private lateinit var campoApiKey: EditText
    private lateinit var campoModelo: EditText

    private val pantallaLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                ScreenCapture.guardarPermiso(result.resultCode, data)
                CapriPrefs.saveConfig(
                    this,
                    CapriPrefs.getProvider(this),
                    CapriPrefs.getApiKey(this),
                    CapriPrefs.getModel(this),
                    true
                )
                // Si el servicio ya corre, hay que re-declarar el tipo mediaProjection
                if (CapriService.isRunning) {
                    startForegroundService(Intent(this, CapriService::class.java))
                }
                Toast.makeText(this, "Listo, Capri ya puede ver tu pantalla.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "No diste el permiso, Capri seguirá sin ver la pantalla.", Toast.LENGTH_SHORT).show()
            }
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "Elegí tu IA para Capri"
            textSize = 20f
            setPadding(0, 0, 0, dp(8))
        })

        root.addView(TextView(this).apply {
            text = "Las que dicen \"ve pantalla\" reciben una captura junto a tu pregunta."
            setPadding(0, 0, 0, dp(8))
        })

        grupoProveedor = RadioGroup(this)
        AIProvider.entries.forEach { p ->
            grupoProveedor.addView(RadioButton(this).apply {
                text = p.displayName
                tag = p.name
                id = android.view.View.generateViewId()
            })
        }
        root.addView(grupoProveedor)

        root.addView(TextView(this).apply {
            text = "Tu clave de API (se guarda cifrada solo en este celular):"
            setPadding(0, dp(16), 0, dp(8))
        })

        campoApiKey = EditText(this).apply {
            hint = "Pegá tu API key acá"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(campoApiKey)

        root.addView(TextView(this).apply {
            text = "Modelo (opcional, dejalo vacío si no sabés):"
            setPadding(0, dp(12), 0, dp(8))
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

        // Se registra DESPUÉS de cargar, para no borrar el modelo guardado al marcar el radio inicial
        grupoProveedor.setOnCheckedChangeListener { group, checkedId ->
            val p = AIProvider.fromId(group.findViewById<RadioButton>(checkedId)?.tag as? String)
            campoModelo.setText("")
            campoModelo.hint = p.defaultModel
        }
    }

    private fun proveedorSeleccionado(): AIProvider {
        val id = grupoProveedor.checkedRadioButtonId
        val tag = if (id != -1) grupoProveedor.findViewById<RadioButton>(id)?.tag as? String else null
        return AIProvider.fromId(tag)
    }

    private fun cargarConfiguracionActual() {
        val provider = CapriPrefs.getProvider(this)
        campoApiKey.setText(CapriPrefs.getApiKey(this))
        campoModelo.hint = provider.defaultModel

        val guardado = CapriPrefs.getModel(this)
        if (guardado != provider.defaultModel) campoModelo.setText(guardado)

        for (i in 0 until grupoProveedor.childCount) {
            val rb = grupoProveedor.getChildAt(i) as RadioButton
            if (rb.tag == provider.name) rb.isChecked = true
        }
    }

    private fun pedirPermisoPantalla() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        pantallaLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun guardarConfiguracion() {
        val provider = proveedorSeleccionado()
        val modelo = campoModelo.text.toString().trim().ifBlank { provider.defaultModel }
        val apiKey = campoApiKey.text.toString().trim()

        CapriPrefs.saveConfig(this, provider, apiKey, modelo, CapriPrefs.isVisionEnabled(this))
        Toast.makeText(this, "Configuración guardada.", Toast.LENGTH_SHORT).show()
        finish()
    }
}
