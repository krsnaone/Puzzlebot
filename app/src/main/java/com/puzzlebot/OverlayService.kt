package com.puzzlebot

import android.app.*
import android.content.*
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class OverlayService : Service() {

    companion object {
        var tapDelayMs: Long = 600
        const val CHANNEL_ID = "puzzlebot_channel"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0
    private var windowManager: WindowManager? = null
    private var overlayRoot: View? = null
    private var fabSolve: com.google.android.material.floatingactionbutton.FloatingActionButton? = null
    private var tvStatus: TextView? = null
    private var dotView: DotOverlayView? = null
    private var solving = false
    private var numbers = mutableListOf<NumberResult>()
    private val handler = Handler(Looper.getMainLooper())
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager!!.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, buildNotification())
        val resultCode = intent?.getIntExtra("resultCode", -1) ?: return START_NOT_STICKY
        val data = intent.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, data)
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay("PuzzleBot",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null)
        showOverlay()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        solving = false
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        overlayRoot?.let { try { windowManager?.removeView(it) } catch (e: Exception) {} }
        dotView?.let { try { windowManager?.removeView(it) } catch (e: Exception) {} }
    }

    private fun showOverlay() {
        val inflater = LayoutInflater.from(this)
        overlayRoot = inflater.inflate(R.layout.overlay_button, null)
        fabSolve = overlayRoot!!.findViewById(R.id.fabSolve)
        tvStatus = overlayRoot!!.findViewById(R.id.tvFabStatus)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 16; y = 200 }

        var startX = 0f; var startY = 0f; var initX = 0; var initY = 0; var dragging = false
        overlayRoot!!.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { startX = e.rawX; startY = e.rawY; initX = params.x; initY = params.y; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(e.rawX - startX) > 5 || Math.abs(e.rawY - startY) > 5) dragging = true
                    if (dragging) { params.x = (initX - (e.rawX - startX)).toInt(); params.y = (initY + (e.rawY - startY)).toInt(); windowManager!!.updateViewLayout(overlayRoot, params) }
                }
            }
            dragging
        }

        fabSolve!!.setOnClickListener { if (solving) cancelSolve() else startSolve() }
        windowManager!!.addView(overlayRoot, params)

        dotView = DotOverlayView(this)
        val dotParams = WindowManager.LayoutParams(screenWidth, screenHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
        windowManager!!.addView(dotView, dotParams)
    }

    private fun startSolve() {
        solving = true
        setStatus("Scanning…", Color.parseColor("#61B3FF"))
        dotView?.clear()
        handler.postDelayed({
            val image: Image? = imageReader?.acquireLatestImage()
            if (image == null) { setStatus("Retry", Color.parseColor("#4FFFB0")); solving = false; return@postDelayed }
            val planes = image.planes
            val buffer = planes[0].buffer
            val rowPadding = planes[0].rowStride - planes[0].pixelStride * screenWidth
            val bmp = Bitmap.createBitmap(screenWidth + rowPadding / planes[0].pixelStride, screenHeight, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buffer)
            image.close()
            val cropped = Bitmap.createBitmap(bmp, 0, 0, screenWidth, screenHeight)
            val inputImage = InputImage.fromBitmap(cropped, 0)
            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val found = mutableMapOf<Int, NumberResult>()
                    for (block in visionText.textBlocks)
                        for (line in block.lines)
                            for (el in line.elements) {
                                val n = el.text.trim().toIntOrNull() ?: continue
                                if (n < 1 || n > 9999) continue
                                val box = el.boundingBox ?: continue
                                if (!found.containsKey(n))
                                    found[n] = NumberResult(n, (box.left+box.right)/2f, (box.top+box.bottom)/2f)
                            }
                    numbers = found.values.sortedBy { it.number }.toMutableList()
                    if (numbers.isEmpty()) { setStatus("No numbers found", Color.parseColor("#FF6B6B")); solving = false; return@addOnSuccessListener }
                    dotView?.setNumbers(numbers)
                    setStatus("Solving…", Color.parseColor("#FF6B6B"))
                    tapSequence(0)
                }
                .addOnFailureListener { setStatus("OCR failed", Color.parseColor("#FF6B6B")); solving = false }
        }, 400)
    }

    private fun tapSequence(index: Int) {
        if (!solving || index >= numbers.size) {
            solving = false; setStatus("Done! ✓", Color.parseColor("#4FFFB0")); return
        }
        val t = numbers[index]
        dotView?.highlight(index)
        setStatus("${index+1}/${numbers.size} → #${t.number}", Color.parseColor("#FF6B6B"))
        val acc = PuzzleAccessibilityService.instance
        if (acc == null) { Toast.makeText(this, "Enable Accessibility Service!", Toast.LENGTH_SHORT).show(); solving = false; return }
        acc.tap(t.screenX, t.screenY) { handler.postDelayed({ tapSequence(index + 1) }, tapDelayMs) }
    }

    private fun cancelSolve() {
        solving = false; numbers.clear(); dotView?.clear(); setStatus("Tap to solve", Color.parseColor("#4FFFB0"))
    }

    private fun setStatus(text: String, color: Int) {
        handler.post { tvStatus?.text = text; fabSolve?.backgroundTintList = android.content.res.ColorStateList.valueOf(color) }
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "PuzzleBot", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("PuzzleBot Active")
        .setContentText("Tap the green button to solve your puzzle")
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setOngoing(true).build()
}

class DotOverlayView(context: Context) : View(context) {
    private val nums = mutableListOf<NumberResult>()
    private var hlIdx = -1
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#994FFFB0"); style = Paint.Style.FILL }
    private val pHL = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#CCFF6B6B"); style = Paint.Style.FILL }
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4FFFB0"); style = Paint.Style.STROKE; strokeWidth = 3f }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 28f; textAlign = Paint.Align.CENTER; typeface = Typeface.MONOSPACE; isFakeBoldText = true }
    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#554FFFB0"); strokeWidth = 2f; pathEffect = DashPathEffect(floatArrayOf(8f,8f),0f) }

    fun setNumbers(n: List<NumberResult>) { nums.clear(); nums.addAll(n); hlIdx = -1; postInvalidate() }
    fun highlight(i: Int) { hlIdx = i; postInvalidate() }
    fun clear() { nums.clear(); hlIdx = -1; postInvalidate() }

    override fun onDraw(canvas: Canvas) {
        for (i in 0 until nums.size - 1)
            canvas.drawLine(nums[i].screenX, nums[i].screenY, nums[i+1].screenX, nums[i+1].screenY, pLine)
        nums.forEachIndexed { i, n ->
            canvas.drawCircle(n.screenX, n.screenY, if (i == hlIdx) 30f else 22f, if (i == hlIdx) pHL else pFill)
            canvas.drawCircle(n.screenX, n.screenY, if (i == hlIdx) 30f else 22f, pStroke)
            canvas.drawText(n.number.toString(), n.screenX, n.screenY + pText.textSize/3, pText)
        }
    }
}