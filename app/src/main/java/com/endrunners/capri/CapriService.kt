package com.endrunners.capri

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.SearchManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

class CapriService : Service(), TextToSpeech.OnInitListener {

    companion object {
        @Volatile
        var isRunning = false

        private const val NOTIF_ID = 1
        private const val CHANNEL_ID = "capri_core_channel"
        private const val SPRITE_DP = 70
        private const val UTT_DEFAULT = "capri_utterance"
        private const val UTT_GREETING = "capri_greeting"
        private val LOCALE_ES = Locale.forLanguageTag("es-MX")
    }

    private lateinit var windowManager: WindowManager
    private lateinit var capriContainer: LinearLayout
    private lateinit var capriSprite: ImageView
    private lateinit var speechBubble: TextView
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var tts: TextToSpeech
    private var speechRecognizer: SpeechRecognizer? = null

    private var viewAttached = false
    private var isListening = false
    private var flashOn = false
    private var torchCamId: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private var isWalking = true
    private var directionX = 0

    // ---------- Runnables reutilizables (para poder cancelarlos) ----------

    private val hideBubbleRunnable = Runnable { speechBubble.visibility = View.GONE }
    private val resumeWalkRunnable = Runnable { isWalking = true }
    private val startListeningRunnable = Runnable { startListening() }
    private val resetListeningRunnable = Runnable { isListening = false }

    private val walkRunnable = object : Runnable {
        override fun run() {
            if (!viewAttached) return
            if (isWalking && !isListening) {
                val maxX = (resources.displayMetrics.widthPixels - capriContainer.width).coerceAtLeast(0)
                params.x += directionX
                if (params.x > maxX || params.x < 0) {
                    params.x = params.x.coerceIn(0, maxX)
                    directionX = -directionX
                    capriSprite.scaleX = if (directionX > 0) 1f else -1f
                }
                windowManager.updateViewLayout(capriContainer, params)

                if (Random.nextInt(100) < 2) {
                    isWalking = false
                    handler.removeCallbacks(resumeWalkRunnable)
                    handler.postDelayed(resumeWalkRunnable, 3000)
                }
            }
            handler.postDelayed(this, 35)
        }
    }

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchCamId) flashOn = enabled
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------- Ciclo de vida ----------

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        // startForeground tiene que llamarse pronto tras startForegroundService, incluso si luego abortamos
        startForegroundNotification()

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        tts = TextToSpeech(this, this)
        setupTorch()
        setupFloatingCharacter()
        startAutonomousBehavior()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Se vuelve a declarar el tipo (p. ej. cuando se concede la captura de pantalla con el servicio ya activo)
        startForegroundNotification()
        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Capri Core", NotificationManager.IMPORTANCE_LOW)
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Capri // End Runners OS")
            .setContentText("Sistema activo en pantalla")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        if (Build.VERSION.SDK_INT < 29) {
            startForeground(NOTIF_ID, notification)
            return
        }

        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

        var base = 0
        if (Build.VERSION.SDK_INT >= 34) base = base or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (Build.VERSION.SDK_INT >= 30 && micOk) base = base or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

        val conProyeccion = base or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        val tipo = if (ScreenCapture.tienePermiso()) conProyeccion else base

        try {
            startForeground(NOTIF_ID, notification, tipoOManifest(tipo))
        } catch (e: SecurityException) {
            // Permiso de captura caducado o no válido: arrancar sin el tipo mediaProjection
            ScreenCapture.liberar()
            startForeground(NOTIF_ID, notification, tipoOManifest(base))
        }
    }

    private fun tipoOManifest(tipo: Int) =
        if (tipo != 0) tipo else ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        speechRecognizer = null
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        try {
            (getSystemService(Context.CAMERA_SERVICE) as CameraManager).unregisterTorchCallback(torchCallback)
        } catch (_: Exception) {
        }
        if (viewAttached) {
            try {
                windowManager.removeView(capriContainer)
            } catch (_: Exception) {
            }
            viewAttached = false
        }
        ScreenCapture.liberar()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- Personaje flotante ----------

    private fun setupFloatingCharacter() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        capriContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        speechBubble = TextView(this).apply {
            setBackgroundColor(0xDD0D0D12.toInt())
            setTextColor(0xFF00F0FF.toInt())
            setPadding(dp(10), dp(6), dp(10), dp(6))
            textSize = 12f
            maxWidth = dp(240)
            visibility = View.GONE
        }

        capriSprite = ImageView(this).apply {
            setImageResource(R.drawable.capri_sprite)
            layoutParams = LinearLayout.LayoutParams(dp(SPRITE_DP), dp(SPRITE_DP))
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
            x = dp(40)
            y = dp(200)
        }

        setupInteractions()

        try {
            windowManager.addView(capriContainer, params)
            viewAttached = true
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun setupInteractions() {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false
        val umbral = dp(4)

        capriSprite.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isWalking = false
                    handler.removeCallbacks(resumeWalkRunnable)
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
                    if (abs(dx) > umbral || abs(dy) > umbral) moved = true
                    if (viewAttached) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager.updateViewLayout(capriContainer, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (event.action == MotionEvent.ACTION_UP && !moved) onCapriTapped()
                    handler.removeCallbacks(resumeWalkRunnable)
                    handler.postDelayed(resumeWalkRunnable, 4000)
                    true
                }
                else -> false
            }
        }
    }

    private fun startAutonomousBehavior() {
        directionX = dp(2)
        handler.post(walkRunnable)
    }

    // ---------- Voz: hablar y escuchar ----------

    private fun onCapriTapped() {
        if (isListening) return

        val saludos = listOf(
            "Te escucho, corredor.",
            "Dime qué necesitas.",
            "Capri en línea. Adelante.",
            "Sistema listo para recibir órdenes."
        )
        handler.removeCallbacks(startListeningRunnable)
        speakAndShow(saludos.random(), UTT_GREETING)
        // Respaldo: normalmente se empieza a escuchar cuando el TTS termina el saludo (ver onInit)
        handler.postDelayed(startListeningRunnable, 3000)
    }

    private fun startListening() {
        if (isListening) return

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
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
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    finishListening()
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                            speakAndShow("No te escuché bien, repetime.")
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> Unit
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                            speakAndShow("Necesito permiso de micrófono. Abrí la app y concedémelo.")
                        else -> speakAndShow("Falló el reconocimiento de voz (código $error).")
                    }
                }

                override fun onResults(results: Bundle?) {
                    finishListening()
                    val texto = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.lowercase(LOCALE_ES) ?: ""
                    if (texto.isNotBlank()) executeCommand(texto)
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, p: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX")
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }

        isListening = true
        // Si el reconocedor nunca responde, no dejar a Capri bloqueado
        handler.removeCallbacks(resetListeningRunnable)
        handler.postDelayed(resetListeningRunnable, 15000)
        speechRecognizer?.startListening(intent)
    }

    private fun finishListening() {
        isListening = false
        handler.removeCallbacks(resetListeningRunnable)
    }

    private fun speakAndShow(text: String, utteranceId: String = UTT_DEFAULT) {
        speechBubble.text = text
        speechBubble.visibility = View.VISIBLE
        if (::tts.isInitialized) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
        handler.removeCallbacks(hideBubbleRunnable)
        handler.postDelayed(hideBubbleRunnable, maxOf(5000L, text.length * 70L))
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts.language = LOCALE_ES
        tts.setPitch(0.95f)
        tts.setSpeechRate(1.08f)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                // Escuchar solo cuando Capri terminó de hablar, para que no se oiga a sí mismo
                if (utteranceId == UTT_GREETING) {
                    handler.post {
                        handler.removeCallbacks(startListeningRunnable)
                        startListening()
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {}
        })
    }

    // ---------- Comandos ----------

    private fun normalizar(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    private fun executeCommand(original: String) {
        val texto = normalizar(original)
        val launchFlags = Intent.FLAG_ACTIVITY_NEW_TASK

        // Primero lo más específico, después lo genérico
        val subirVolumen = Regex("\\b(sube|subi|subir|aumenta|aumentar)\\b.*\\bvolumen\\b").containsMatchIn(texto)
        val bajarVolumen = Regex("\\b(baja|bajar|disminuye|reduce|reducir)\\b.*\\bvolumen\\b").containsMatchIn(texto)
        val llamar = Regex("\\bllam(?:a|ar|ame)\\s+(?:a\\s+)?(.+)").find(texto)
        val buscar = Regex("\\b(?:busca|buscar|buscame)\\s+(.+)").find(original)
        val esLinterna = Regex("\\b(linterna|flash)\\b").containsMatchIn(texto) &&
            texto.split(" ").size <= 5

        when {
            subirVolumen -> {
                cambiarVolumen(true)
                speakAndShow("Subiendo volumen.")
            }

            bajarVolumen -> {
                cambiarVolumen(false)
                speakAndShow("Bajando volumen.")
            }

            llamar != null -> {
                val numero = llamar.groupValues[1].filter { it.isDigit() }
                if (numero.length >= 7) {
                    startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$numero")).addFlags(launchFlags))
                    speakAndShow("Marcando el número.")
                } else {
                    speakAndShow("Decime el número completo para llamar.")
                }
            }

            buscar != null -> {
                val query = buscar.groupValues[1].trim()
                val searchIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                    putExtra(SearchManager.QUERY, query)
                    addFlags(launchFlags)
                }
                try {
                    startActivity(searchIntent)
                    speakAndShow("Buscando $query")
                } catch (e: Exception) {
                    speakAndShow("No pude abrir el buscador.")
                }
            }

            esLinterna -> toggleLinterna()

            "whatsapp" in texto -> abrirApp("com.whatsapp", "No tenés WhatsApp instalado.")

            "youtube" in texto -> abrirApp("com.google.android.youtube", "No tenés YouTube instalado.")

            "camara" in texto -> {
                try {
                    startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(launchFlags))
                    speakAndShow("Abriendo la cámara.")
                } catch (e: Exception) {
                    speakAndShow("No pude abrir la cámara.")
                }
            }

            "configuracion" in texto || "ajustes" in texto -> {
                try {
                    startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(launchFlags))
                    speakAndShow("Abriendo configuración.")
                } catch (e: Exception) {
                    speakAndShow("No pude abrir la configuración.")
                }
            }

            else -> {
                speakAndShow("Dejame pensarlo...")
                AIClient.ask(
                    this,
                    original,
                    {
                        if (CapriPrefs.isVisionEnabled(this) && ScreenCapture.tienePermiso()) {
                            ScreenCapture.capturarComoBase64(this)
                        } else null
                    }
                ) { respuesta ->
                    handler.post { speakAndShow(respuesta) }
                }
            }
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

    // ---------- Linterna y volumen ----------

    private fun setupTorch() {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            torchCamId = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            // El callback mantiene flashOn sincronizado si otra app enciende/apaga el flash
            cm.registerTorchCallback(torchCallback, handler)
        } catch (_: Exception) {
        }
    }

    private fun togtexto,
                    { if (CapriPrefs.isVisionEnabled(this) && ScreenCapture.tienePermiso()) ScreenCapture.capturarComoBase64(this) else null }
                ) { respuesta ->
                    handler.post { speakAndShow(respuesta) }
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
