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
import android.widget.LinearLayout
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

// Kedi boyutu ayarları (kutu boyutuna oran). Küçültmek için sayıları düşürün.
const val CAT_TEXT = 0.25f
const val CAT_CIRCLE = 0.17f

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
                        "Başlat'a basın, oyuna geçin. Bölüm başında temiz tahtada " +
                            "Hesapla'ya basın. Sonra Göster / Gizle ile kedileri açıp kapatın. " +
                            "Düğmeleri sürükleyerek taşıyabilirsiniz."
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
    private var panel: LinearLayout? = null
    private var catView: CatView? = null
    private var banner: TextView? = null
    private var lastFound: Found? = null
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
        addPanel()
        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("cat", "Kedi çözücü", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "cat")
            .setContentTitle("Kedi çözücü çalışıyor")
            .setContentText("Oyunda Hesapla / Göster / Gizle düğmelerini kullanın")
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

    // Sürüklenebilen üç düğmelik panel: Hesapla / Göster / Gizle
    private fun addPanel() {
        val d = resources.displayMetrics.density
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 16
        lp.y = 400

        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false

        fun addBtn(label: String, action: () -> Unit) {
            val tv = TextView(this)
            tv.text = label
            tv.textSize = 13f
            tv.gravity = Gravity.CENTER
            tv.setTextColor(0xFF4A2F2F.toInt())
            tv.setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
            val bgd = GradientDrawable()
            bgd.cornerRadius = 20 * d
            bgd.setColor(0xE6FFFFFF.toInt())
            tv.background = bgd
            tv.setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - sx).toInt()
                        val dy = (e.rawY - sy).toInt()
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) moved = true
                        if (moved) {
                            lp.x = ox + dx
                            lp.y = oy + dy
                            wm.updateViewLayout(box, lp)
                        }
                    }
                    MotionEvent.ACTION_UP -> if (!moved) action()
                }
                true
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.bottomMargin = (4 * d).toInt()
            box.addView(tv, params)
        }

        addBtn("🔍 Hesapla") { calculate() }
        addBtn("👁 Göster") { showSaved() }
        addBtn("🚫 Gizle") { removeCats() }

        wm.addView(box, lp)
        panel = box
    }

    private fun say(msg: String) {
        removeBanner()
        val tv = TextView(this)
        tv.text = msg
        tv.setTextColor(Color.WHITE)
        tv.textSize = 14f
        tv.setPadding(32, 20, 32, 20)
        val bgd = GradientDrawable()
        bgd.setColor(0xE6000000.toInt())
        bgd.cornerRadius = 24f
        tv.background = bgd
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.y = 150
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

    // Ekranı yakalayıp çözer, sonucu hafızaya alır ve gösterir.
    private fun calculate() {
        if (busy) return
        busy = true
        removeBanner()
        removeCats()
        panel?.visibility = View.INVISIBLE
        ui.postDelayed({
            val bmp = try { grab() } catch (e: Throwable) { null }
            panel?.visibility = View.VISIBLE
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
                        if (f == null) {
                            say(res.second)
                        } else {
                            lastFound = f
                            showCats(f)
                        }
                        busy = false
                    }
                }
            }
        }, 300)
    }

    private fun showSaved() {
        val f = lastFound
        if (f == null) {
            say("Önce Hesapla'ya basın")
            return
        }
        if (catView == null) showCats(f)
    }

    private fun showCats(f: Found) {
        removeCats()
        val v = CatView(this, f)
        val lp = WindowManager.LayoutParams(
            screenW, screenH, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        wm.addView(v, lp)
        catView = v
        // Paneli kedilerin üstünde tut
        panel?.let { p ->
            val plp = p.layoutParams as WindowManager.LayoutParams
            wm.removeView(p)
            wm.addView(p, plp)
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
        panel?.let { try { wm.removeView(it) } catch (e: Exception) { } }
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
        textSize = f.cell * CAT_TEXT
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        for (p in f.points) {
            canvas.drawCircle(p.x, p.y, f.cell * CAT_CIRCLE, circle)
            canvas.drawText("🐱", p.x, p.y + text.textSize * 0.35f, text)
        }
    }
}

class Found(val points: List<PointF>, val cell: Float)

// 8 örnek noktadan çoğunluk rengini seçer (ikon, çarpı veya kedi birkaç noktayı bozsa da çalışır)
fun voteColor(s: IntArray): Int {
    var best = s[0]
    var bestN = -1
    for (a in s) {
        var k = 0
        for (b in s) if (dist(a, b) < 30) k++
        if (k > bestN) { bestN = k; best = a }
    }
    return best
}

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

    val cellColor = IntArray(n * n)
    for (r in 0 until n) {
        for (c in 0 until n) {
            val x0 = colRuns[c].first
            val x1 = colRuns[c].last
            val y0 = rowRuns[r].first
            val y1 = rowRuns[r].last
            val cx = (x0 + x1) / 2
            val cy = (y0 + y1) / 2
            val dx = ((x1 - x0) * 0.1f).toInt()
            val dy = ((y1 - y0) * 0.1f).toInt()
            val s = intArrayOf(
                px[(y0 + dy) * w + cx],
                px[(y1 - dy) * w + cx],
                px[cy * w + x0 + dx],
                px[cy * w + x1 - dx],
                px[(y0 + dy) * w + x0 + dx],
                px[(y0 + dy) * w + x1 - dx],
                px[(y1 - dy) * w + x0 + dx],
                px[(y1 - dy) * w + x1 - dx]
            )
            cellColor[r * n + c] = voteColor(s)
        }
    }

    val palette = mutableListOf<Int>()
    val ids = IntArray(n * n)
    for (i in cellColor.indices) {
        var idx = palette.indexOfFirst { dist(it, cellColor[i]) < 40 }
        if (idx == -1) { palette.add(cellColor[i]); idx = palette.size - 1 }
        ids[i] = idx
    }

    // Fazla renk varsa en yakın renk çiftlerini birleştir
    val alive = MutableList(palette.size) { true }
    var count = palette.size
    while (count > n) {
        var ba = -1
        var bb = -1
        var bd = Int.MAX_VALUE
        for (a in palette.indices) {
            if (!alive[a]) continue
            for (b in a + 1 until palette.size) {
                if (!alive[b]) continue
                val dd = dist(palette[a], palette[b])
                if (dd < bd) { bd = dd; ba = a; bb = b }
            }
        }
        for (i in ids.indices) if (ids[i] == bb) ids[i] = ba
        alive[bb] = false
        count--
    }
    if (count < n) return Pair(null, "Renk sayısı $count, olması gereken $n")

    val map = HashMap<Int, Int>()
    val regions = Array(n) { r ->
        IntArray(n) { c ->
            map.getOrPut(ids[r * n + c]) { map.size }
        }
    }

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

// Doygun renkli ya da beyazdan belirgin koyu pikseller kutu sayılır (soluk kutular dahil)
fun isTile(p: Int): Boolean {
    val r = (p shr 16) and 255
    val g = (p shr 8) and 255
    val b = p and 255
    val mx = maxOf(r, g, b)
    val mn = minOf(r, g, b)
    return (mx - mn > 30) || mn < 225
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
