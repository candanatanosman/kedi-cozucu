package com.example.catsolver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val capture = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        val d = r.data
        if (r.resultCode == RESULT_OK && d != null) {
            val i = Intent(this, OverlayService::class.java)
                .putExtra("code", r.resultCode)
                .putExtra("data", d)
            ContextCompat.startForegroundService(this, i)
            moveTaskToBack(true)
        }
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this, "Önce 'Diğer uygulamaların üzerinde göster' iznini verin",
                Toast.LENGTH_LONG
            ).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        capture.launch(mpm.createScreenCaptureIntent())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Column(
                    Modifier.fillMaxSize().statusBarsPadding().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("Kedi Çözücü", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Başlat'a basın, oyuna geçin, 🐱 düğmesine dokunun. " +
                            "Her dokunuş yeniden çözer. Kedileri kaldırmak için düğmeye uzun basın."
                    )
                    Button(onClick = { start() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Başlat")
                    }
                    OutlinedButton(
                        onClick = { stopService(Intent(this@MainActivity, OverlayService::class.java)) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Durdur") }
                }
            }
        }
    }
}

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vd: VirtualDisplay? = null
    private var held: Image? = null
    private val lock = Any()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var bgThread: HandlerThread
    private lateinit var bg: Handler
    private var button: TextView? = null
    private var catView: CatView? = null
    private var banner: TextView? = null
    private var busy = false
    private var screenW = 0
    private var screenH = 0

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        startForegroundNotification()

        val code = intent.getIntExtra("code", 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra("data", Intent::class.java)
        else intent.getParcelableExtra("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(code, data)
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, ui)
        projection = mp
        setupCapture()
        addButton()
        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("cat", "Kedi çözücü", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "cat")
            .setContentTitle("Kedi çözücü çalışıyor")
            .setContentText("Oyunda 🐱 düğmesine dokunun")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun setupCapture() {
        val b = wm.currentWindowMetrics.bounds
        screenW = b.width()
        screenH = b.height()
        bgThread = HandlerThread("cap")
        bgThread.start()
        bg = Handler(bgThread.looper)
        val r = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ rd ->
            val img = rd.acquireLatestImage()
            if (img != null) {
                synchronized(lock) {
                    held?.close()
                    held = img
                }
            }
        }, bg)
        reader = r
        vd = projection!!.createVirtualDisplay(
            "cat-cap", screenW, screenH, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, null
        )
    }

    private fun grab(): Bitmap? {
        synchronized(lock) {
            val img = held ?: return null
            val p = img.planes[0]
            p.buffer.rewind()
            val rowPad = p.rowStride - p.pixelStride * img.width
            val tmp = Bitmap.createBitmap(
                img.width + rowPad / p.pixelStride, img.height, Bitmap.Config.ARGB_8888
            )
            tmp.copyPixelsFromBuffer(p.buffer)
            return Bitmap.createBitmap(tmp, 0, 0, img.width, img.height)
        }
    }

    private fun addButton() {
        val size = (56 * resources.displayMetrics.density).toInt()
        val tv = TextView(this).apply {
            text = "🐱"
            textSize = 26f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE6FFFFFF.toInt())
            }
        }
        val lp = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 16; y = 400 }

        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        var downT = 0L
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false
                    downT = System.currentTimeMillis()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - sx).toInt()
                    val dy = (e.rawY - sy).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) moved = true
                    if (moved) { lp.x = ox + dx; lp.y = oy + dy; wm.updateViewLayout(tv, lp) }
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    if (System.currentTimeMillis() - downT > 600) {
                        removeCats()
                    } else {
                        toggle()
                    }
                }
            }
            true
        }
        wm.addView(tv, lp)
        button = tv
    }

    // Ekranın üstünde kısa mesaj kutusu (toast bazı telefonlarda arka planda görünmüyor)
    private fun say(msg: String) {
        removeBanner()
        val tv = TextView(this).apply {
            text = msg
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(32, 20, 32, 20)
            background = GradientDrawable().apply {
                setColor(0xE6000000.toInt())
                cornerRadius = 24f
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 150 }
        wm.addView(tv, lp)
        banner = tv
        ui.postDelayed({
            if (banner === tv) removeBanner()
        }, 3000)
    }

    private fun removeBanner() {
        val b = banner ?: return
        try { wm.removeView(b) } catch (e: Exception) { }
        banner = null
    }

    private fun toggle() {
        if (busy) return
        busy = true
        removeBanner()
        removeCats()
        button?.visibility = View.INVISIBLE
        // Kedilerin kalkması, düğmenin gizlenmesi ve yeni karenin gelmesi için bekleme
        ui.postDelayed({
            val bmp = try { grab() } catch (e: Throwable) { null }
            button?.visibility = View.VISIBLE
            if (bmp == null) {
                say("Ekran karesi alınamadı")
                busy = false
            } else {
                bg.post {
                    val res: Pair<Found?, String> = try {
                        analyze(bmp)
                    } catch (e: Throwable) {
                        Pair(null, "Hata: " + e.javaClass.simpleName)
                    }
                    ui.post {
                        val f = res.first
                        if (f == null) say(res.second) else showCats(f)
                        busy = false
                    }
                }
            }
        }, 300)
    }

    private fun showCats(f: Found) {
        val v = CatView(this, f)
        val lp = WindowManager.LayoutParams(
            screenW, screenH, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        wm.addView(v, lp)
        catView = v
        button?.let { b ->
            val blp = b.layoutParams as WindowManager.LayoutParams
            wm.removeView(b)
            wm.addView(b, blp)
        }
    }

    private fun removeCats() {
        val v = catView ?: return
        try { wm.removeView(v) } catch (e: Exception) { }
        catView = null
    }

    override fun onDestroy() {
        removeBanner()
        removeCats()
        button?.let { wm.removeView(it) }
        vd?.release()
        reader?.close()
        synchronized(lock) { held?.close(); held = null }
        projection?.stop()
        bgThread.quitSafely()
        super.onDestroy()
    }
}

class CatView(ctx: Context, private val f: Found) : View(ctx) {
    private val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt() }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = f.cell * 0.6f
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        for (p in f.points) {
            canvas.drawCircle(p.x, p.y, f.cell * 0.38f, circle)
            canvas.drawText("🐱", p.x, p.y + text.textSize * 0.35f, text)
        }
    }
}

class Found(val points: List<PointF>, val cell: Float)

fun analyze(bmp: Bitmap): Pair<Found?, String> {
    val w = bmp.width
    val h = bmp.height
    val px = IntArray(w * h)
    bmp.getPixels(px, 0, w, 0, 0, w, h)

    val rowFlag = BooleanArray(h) { y ->
        var cnt = 0
        for (x in 0 until w) if (isTile(px[y * w + x])) cnt++
        cnt > w * 0.7
    }
    val rowRuns = runs(rowFlag, 10)
    if (rowRuns.size < 4) return Pair(null, "Izgara bulunamadı")
    val top = rowRuns.first().first
    val bottom = rowRuns.last().last
    val bandH = bottom - top + 1

    val colFlag = BooleanArray(w) { x ->
        var cnt = 0
        for (y in top..bottom) if (isTile(px[y * w + x])) cnt++
        cnt > bandH * 0.5
    }
    val colRuns = runs(colFlag, 10)
    val n = rowRuns.size
    if (colRuns.size != n) return Pair(null, "Satır ($n) / sütun (${colRuns.size}) uyuşmuyor")

    val palette = mutableListOf<Int>()
    val regions = Array(n) { r ->
        IntArray(n) { c ->
            val x = colRuns[c].first + ((colRuns[c].last - colRuns[c].first) * 0.18f).toInt()
            val y = rowRuns[r].first + ((rowRuns[r].last - rowRuns[r].first) * 0.18f).toInt()
            val p = px[y * w + x]
            var idx = palette.indexOfFirst { dist(it, p) < 24 }
            if (idx == -1) { palette.add(p); idx = palette.size - 1 }
            idx
        }
    }
    if (palette.size != n) return Pair(null, "Renk sayısı ${palette.size}, olması gereken $n")

    val sol = solve(regions) ?: return Pair(null, "Çözüm bulunamadı")
    val pts = List(n) { r ->
        val c = sol[r]
        PointF(
            (colRuns[c].first + colRuns[c].last) / 2f,
            (rowRuns[r].first + rowRuns[r].last) / 2f
        )
    }
    val cell = (rowRuns[0].last - rowRuns[0].first + 1).toFloat()
    return Pair(Found(pts, cell), "ok")
}

fun isTile(p: Int): Boolean {
    val r = (p shr 16) and 255
    val g = (p shr 8) and 255
    val b = p and 255
    return maxOf(r, g, b) - minOf(r, g, b) > 30
}

fun runs(flags: BooleanArray, minLen: Int): List<IntRange> {
    val out = mutableListOf<IntRange>()
    var start = -1
    for (i in 0..flags.size) {
        val on = i < flags.size && flags[i]
        if (on && start == -1) start = i
        if (!on && start != -1) {
            if (i - start >= minLen) out.add(start until i)
            start = -1
        }
    }
    return out
}

fun dist(a: Int, b: Int): Int =
    Math.abs(Color.red(a) - Color.red(b)) +
        Math.abs(Color.green(a) - Color.green(b)) +
        Math.abs(Color.blue(a) - Color.blue(b))

fun solve(regions: Array<IntArray>): IntArray? {
    val n = regions.size
    val cols = IntArray(n) { -1 }
    val usedCol = BooleanArray(n)
    val usedReg = BooleanArray(n)

    fun go(r: Int): Boolean {
        if (r == n) return true
        for (c in 0 until n) {
            val g = regions[r][c]
            if (usedCol[c] || usedReg[g]) continue
            if (r > 0 && Math.abs(cols[r - 1] - c) <= 1) continue
            cols[r] = c; usedCol[c] = true; usedReg[g] = true
            if (go(r + 1)) return true
            usedCol[c] = false; usedReg[g] = false
        }
        return false
    }
    return if (go(0)) cols else null
}
