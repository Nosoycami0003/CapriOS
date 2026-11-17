package com.endrunners.capri

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.*
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.random.Random

class CapriService : Service(), TextToSpeech.OnInitListener {

    private lateinit var windowManager: WindowManager
    private lateinit var capriContainer: LinearLayout
    private lateinit var capriSprite: ImageView
    private lateinit var speechBubble: TextView
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var tts: TextToSpeech
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var flashOn = false

    private val handler = Handler(Looper.getMainLooper())
    private var isWalking = true
    private var directionX = 4

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        tts = TextToSpeech(this, this)
        setupFloatingCharacter()
        startAutonomousBehavior()
    }

    private fun startForegroundNotification() {
        val channelId = "capri_core_channel"
        val channel = NotificationChannel(channelId, "Capri Core", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Capri // End Runners OS")
            .setContentText("Sistema activo en pantalla")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        startForeground(1, notification)
    }

    private fun setupFloatingCharacter() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        capriContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        speechBubble = TextView(this).apply {
            setBackgroundColor(0xDD0D0D12.toInt())
            setTextColor(0xFF00F0FF.toInt())
            setPadding(24, 14, 24, 14)
            textSize = 12f
            visibility = View.GONE
        }

        capriSprite = ImageView(this).apply {
            setImageResource(R.drawable.capri_sprite)
            layoutParams = LinearLayout.LayoutParams(180, 180)
        }

        capriContainer.addView(speechBubble)
        capriContainer.addView(capriSprite)

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 500
        }

        setupInteractions()
        windowManager.addView(capriContainer, params)
    }

    private fun setupInteractions() {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false

        capriSprite.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isWalking = false
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) moved = true
                    params.x = initialX + dx
                    params.y = initialY + dy
                    windowManager.updateViewLayout(capriContainer, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        onCapriTapped()
                    }
                    handler.postDelayed({ isWalking = true }, 4000)
                    true
                }
                else -> false
            }
        }
    }

    private fun onCapriTapped() {
        if (isListening) return

        val saludos = listOf(
            "Te escucho, corredor.",
            "Dime qué necesitas.",
            "Capri en línea. Adelante.",
            "Sistema listo para recibir órdenes."
        )
        speakAndShow(saludos.random())
        handler.postDelayed({ startListening() }, 900)
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            speakAndShow("Necesito permiso de micrófono. Abrí la app y concedémelo.")
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speakAndShow("Este teléfono no tiene reconocimiento de voz disponible.")
            return
        }

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { isListening = true }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { isListening = false }
                override fun onError(error: Int) {
                    isListening = false
                    speakAndShow("No te escuché bien, repetime.")
                }
                override fun onResults(results: Bundle?) {
                    isListening = false
                    val texto = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.lowercase(Locale("es", "MX")) ?: ""
                    if (texto.isNotBlank()) executeCommand(texto)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX")
        }
        speechRecognizer?.startListening(intent)
    }

    private fun executeCommand(texto: String) {
        val launchFlags = Intent.FLAG_ACTIVITY_NEW_TASK

        when {
            "whatsapp" in texto -> abrirApp("com.whatsapp", "No tenés WhatsApp instalado.")

            "youtube" in texto -> abrirApp("com.google.android.youtube", "No tenés YouTube instalado.")

            "cámara" in texto || "camara" in texto -> {
                try {
                    startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(launchFlags))
                    speakAndShow("Abriendo la cámara.")
                } catch (e: Exception) {
                    speakAndShow("No pude abrir la cámara.")
                }
            }

            "configuración" in texto || "configuracion" in texto || "ajustes" in texto -> {
                startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(launchFlags))
                speakAndShow("Abriendo configuración.")
            }

            "linterna" in texto || "flash" in texto -> toggleLinterna()

            "sube el volumen" in texto || "subí el volumen" in texto -> {
                cambiarVolumen(true)
                speakAndShow("Subiendo volumen.")
            }

            "baja el volumen" in texto || "bajá el volumen" in texto -> {
                cambiarVolumen(false)
                speakAndShow("Bajando volumen.")
            }

            "llama a" in texto || "llamar a" in texto -> {
                val numero = texto.substringAfter("a ").filter { it.isDigit() }
                if (numero.length >= 7) {
                    startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$numero")).addFlags(launchFlags))
                    speakAndShow("Marcando el número.")
                } else {
                    speakAndShow("Decime el número completo para llamar.")
                }
            }

            "busca" in texto || "buscar" in texto -> {
                val query = texto.substringAfter("buscar").substringAfter("busca").trim()
                val searchIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                    putExtra(android.app.SearchManager.QUERY, query.ifBlank { texto })
                    addFlags(launchFlags)
                }
                try {
                    startActivity(searchIntent)
                    speakAndShow("Buscando $query")
                } catch (e: Exception) {
                    speakAndShow("No pude abrir el buscador.")
                }
            }

            else -> speakAndShow("Todavía no sé hacer eso, pero lo voy a aprender.")
        }
    }

    private fun abrirApp(paquete: String, errorMsg: String) {
        val intent = packageManager.getLaunchIntentForPackage(paquete)
        if (intent != null) {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            speakAndShow("Abriendo.")
        } else {
            speakAndShow(errorMsg)
        }
    }

    private fun toggleLinterna() {
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val camId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (camId != null) {
                flashOn = !flashOn
                cameraManager.setTorchMode(camId, flashOn)
                speakAndShow(if (flashOn) "Linterna encendida." else "Linterna apagada.")
            } else {
                speakAndShow("Este teléfono no tiene linterna.")
            }
        } catch (e: Exception) {
            speakAndShow("No pude controlar la linterna.")
        }
    }

    private fun cambiarVolumen(subir: Boolean) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (subir) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
    }

    private fun startAutonomousBehavior() {
        val screenWidth = resources.displayMetrics.widthPixels

        handler.post(object : Runnable {
            override fun run() {
                if (isWalking) {
                    params.x += directionX
                    if (params.x > screenWidth - 200 || params.x < 0) {
                        directionX = -directionX
                        capriSprite.scaleX = if (directionX > 0) 1f else -1f
                    }
                    windowManager.updateViewLayout(capriContainer, params)

                    if (Random.nextInt(100) < 2) {
                        isWalking = false
                        handler.postDelayed({ isWalking = true }, 3000)
                    }
                }
                handler.postDelayed(this, 35)
            }
        })
    }

    fun speakAndShow(text: String) {
        speechBubble.text = text
        speechBubble.visibility = View.VISIBLE
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "capri_utterance")

        handler.removeCallbacksAndMessages("hide_bubble")
        handler.postAtTime({
            speechBubble.visibility = View.GONE
        }, "hide_bubble", SystemClock.uptimeMillis() + 5000)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("es", "MX")
            tts.setPitch(0.95f)
            tts.setSpeechRate(1.08f)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.shutdown()
        speechRecognizer?.destroy()
        windowManager.removeView(capriContainer)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
