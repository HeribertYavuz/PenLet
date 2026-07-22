package com.deku.penlet

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

/**
 * penlet — S Pen -> PC tablet kopru istemcisi.
 *
 * Area secimi TAMAMEN bu uygulamada yapilir; daemon sadece gelen
 * normalize koordinati gecirir (area mapping burada uygulanir).
 *
 * Protokol: 5-byte, big-endian [uint16 x][uint16 y][uint8 flags]
 *   x,y  : AREA ICINDE normalize -> 0..32767
 *   flags: bit0 = pen down
 *
 * Baglanti: 127.0.0.1:40118, 'adb reverse tcp:40118 tcp:40118' ile PC'ye tunellenir.
 */
class MainActivity : AppCompatActivity() {

    private val host = "127.0.0.1"
    private val port = 40118
    private val axisMax = 32767

    private lateinit var view: AreaView
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var statusText: TextView
    private lateinit var modeBtn: Button
    private lateinit var panel: LinearLayout
    private lateinit var fx: EditText
    private lateinit var fy: EditText
    private lateinit var fw: EditText
    private lateinit var fh: EditText

    @Volatile private var out: OutputStream? = null
    @Volatile private var connected = false
    private val queue = ConcurrentLinkedQueue<ByteArray>()
    private val ui = Handler(Looper.getMainLooper())
    private val PICK_IMAGE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("penlet", Context.MODE_PRIVATE)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        view = AreaView(this)
        view.amoled  = prefs.getBoolean("amoled", true)
        // valueOf bozuk/eski bir deger icin IllegalArgumentException atar -> guvenli oku
        view.pattern = try {
            AreaView.Pattern.valueOf(prefs.getString("pattern", "GRID") ?: "GRID")
        } catch (_: Exception) {
            AreaView.Pattern.GRID
        }
        view.setArea(RectF(
            prefs.getFloat("ax0", 0f), prefs.getFloat("ay0", 0f),
            prefs.getFloat("ax1", 1f), prefs.getFloat("ay1", 1f)
        ))
        loadBackground()

        view.onPenInput = { nx, ny, down -> sendPoint(nx, ny, down) }

        val root = FrameLayout(this)
        root.addView(view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // panel alt kenara yaslansin
        root.addView(buildPanel(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM })
        setContentView(root)

        // onAreaChanged syncFields()'i cagirir -> fx/fy/fw/fh'ye dokunur.
        // Bu alanlar buildPanel() icinde atandigi icin callback'i ANCAK
        // buildPanel()'den SONRA bagliyoruz (lateinit NPE'sini onler).
        view.onAreaChanged = { r -> saveArea(r); syncFields(r) }

        // DIKKAT: setContentView'dan SONRA — DecorView olusmadan
        // window.insetsController null doner ve NPE atar.
        goFullscreen()

        applyMode(AreaView.Mode.SETUP)
        startNetwork()
        startSender()
    }

    // ---------- UI ----------

    private fun buildPanel(): View {
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(220, 12, 12, 14))
            setPadding(24, 20, 24, 20)
        }

        statusText = TextView(this).apply {
            setTextColor(Color.rgb(150, 150, 158))
            textSize = 13f
            text = getString(R.string.status_connecting)
        }
        panel.addView(statusText)

        // --- satir: mod + desen + amoled ---
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 14, 0, 8)
        }

        modeBtn = Button(this).apply {
            text = getString(R.string.mode_activate)
            setOnClickListener {
                applyMode(if (view.mode == AreaView.Mode.SETUP)
                    AreaView.Mode.ACTIVE else AreaView.Mode.SETUP)
            }
        }
        row1.addView(modeBtn)

        val patBtn = Button(this).apply {
            text = patternLabel()
            setOnClickListener {
                view.pattern = when (view.pattern) {
                    AreaView.Pattern.GRID -> AreaView.Pattern.DOTS
                    AreaView.Pattern.DOTS -> AreaView.Pattern.NONE
                    AreaView.Pattern.NONE -> AreaView.Pattern.GRID
                }
                text = patternLabel()
                prefs.edit().putString("pattern", view.pattern.name).apply()
            }
        }
        row1.addView(patBtn)

        val amoBtn = Button(this).apply {
            text = amoledLabel()
            setOnClickListener {
                view.amoled = !view.amoled
                text = amoledLabel()
                prefs.edit().putBoolean("amoled", view.amoled).apply()
            }
        }
        row1.addView(amoBtn)

        val bgBtn = Button(this).apply {
            text = getString(R.string.btn_image)
            setOnClickListener { pickImage() }
        }
        row1.addView(bgBtn)

        panel.addView(row1)

        // --- satir: sayisal giris ---
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fx = numField(getString(R.string.field_x)); fy = numField(getString(R.string.field_y))
        fw = numField(getString(R.string.field_w)); fh = numField(getString(R.string.field_h))
        row2.addView(fx); row2.addView(fy); row2.addView(fw); row2.addView(fh)

        val applyBtn = Button(this).apply {
            text = getString(R.string.btn_apply)
            setOnClickListener { applyFields() }
        }
        row2.addView(applyBtn)

        val fullBtn = Button(this).apply {
            text = getString(R.string.btn_full)
            setOnClickListener { view.setArea(RectF(0f, 0f, 1f, 1f)) }
        }
        row2.addView(fullBtn)

        panel.addView(row2)
        syncFields(view.area)
        return panel
    }

    private fun numField(hint: String) = EditText(this).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        setTextColor(Color.WHITE)
        setHintTextColor(Color.rgb(90, 90, 96))
        textSize = 13f
        width = 150
    }

    private fun syncFields(r: RectF) {
        // Panel henuz kurulmadiysa (erken cagri) sessizce gec — lateinit NPE'sini onler.
        if (!::fx.isInitialized) return
        ui.post {
            // Locale.US: EditText'e yazilan sayiyi sonra toFloat() ile okuyoruz;
            // virgullu ondalik (tr) parse'i bozardi.
            val f = { v: Float -> String.format(java.util.Locale.US, "%.0f", v) }
            fx.setText(f(r.left * 100))
            fy.setText(f(r.top * 100))
            fw.setText(f(r.width() * 100))
            fh.setText(f(r.height() * 100))
        }
    }

    private fun applyFields() {
        val x = fx.text.toString().toFloatOrNull() ?: return
        val y = fy.text.toString().toFloatOrNull() ?: return
        val w = fw.text.toString().toFloatOrNull() ?: return
        val h = fh.text.toString().toFloatOrNull() ?: return
        view.setArea(RectF(x / 100f, y / 100f, (x + w) / 100f, (y + h) / 100f))
    }

    private fun applyMode(m: AreaView.Mode) {
        view.mode = m
        if (m == AreaView.Mode.ACTIVE) {
            modeBtn.text = getString(R.string.mode_back_to_setup)
            panel.visibility = View.GONE          // oyun sirasinda temiz ekran
            // panel gizli -> tekrar acmak icin uzun basma
            view.setOnLongClickListener {
                applyMode(AreaView.Mode.SETUP); true
            }
            view.isLongClickable = true
            toast(getString(R.string.mode_active_hint))
        } else {
            modeBtn.text = getString(R.string.mode_activate)
            panel.visibility = View.VISIBLE
            view.setOnLongClickListener(null)
            view.isLongClickable = false
        }
    }

    private fun goFullscreen() {
        // DIKKAT: window.insetsController getter'inin KENDISI, DecorView henuz
        // olusmamissa NPE atar (?. bunu yakalayamaz — null donen deger degil,
        // getter icinde patliyor). Bu yuzden setContentView'dan sonra cagrilmali
        // ve yine de try-catch ile korunmali.
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false)
                val c = window.insetsController ?: return
                c.hide(android.view.WindowInsets.Type.systemBars())
                c.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility =
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            }
        } catch (_: Exception) {
            // DecorView hazir degil — onWindowFocusChanged'de tekrar denenecek.
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goFullscreen()
    }

    // ---------- arka plan gorseli ----------

    private fun pickImage() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startActivityForResult(i, PICK_IMAGE)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == PICK_IMAGE && res == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {}
                prefs.edit().putString("bg", uri.toString()).apply()
                loadBackground()
            }
        }
    }

    private fun loadBackground() {
        val s = prefs.getString("bg", null) ?: return
        thread(isDaemon = true) {
            try {
                val uri = android.net.Uri.parse(s)
                contentResolver.openInputStream(uri)?.use { ins ->
                    val bmp = BitmapFactory.decodeStream(ins)
                    ui.post { view.bgImage = bmp }
                }
            } catch (_: Exception) {
                ui.post { toast(getString(R.string.toast_image_failed)) }
            }
        }
    }

    // ---------- kayit ----------

    private fun saveArea(r: RectF) {
        prefs.edit()
            .putFloat("ax0", r.left).putFloat("ay0", r.top)
            .putFloat("ax1", r.right).putFloat("ay1", r.bottom)
            .apply()
    }

    // ---------- ag ----------

    private fun sendPoint(nx: Float, ny: Float, down: Boolean) {
        val xi = (nx * axisMax).toInt().coerceIn(0, axisMax)
        val yi = (ny * axisMax).toInt().coerceIn(0, axisMax)
        val flags = if (down) 1 else 0
        val f = byteArrayOf(
            ((xi shr 8) and 0xFF).toByte(), (xi and 0xFF).toByte(),
            ((yi shr 8) and 0xFF).toByte(), (yi and 0xFF).toByte(),
            (flags and 0xFF).toByte()
        )
        if (queue.size > 64) queue.clear()   // geri kalirsa en guncel konumu tut
        queue.add(f)
    }

    private fun startNetwork() = thread(isDaemon = true) {
        while (true) {
            try {
                val s = Socket()
                s.tcpNoDelay = true                       // Nagle kapali — latency
                s.connect(InetSocketAddress(host, port), 3000)
                out = s.getOutputStream()
                connected = true
                setStatus(getString(R.string.status_connected))
                val b = ByteArray(1)
                while (s.getInputStream().read(b) >= 0) { /* canli tut */ }
            } catch (ex: Exception) {
                setStatus(getString(R.string.status_disconnected, port))
            } finally {
                connected = false; out = null
                try { Thread.sleep(1000) } catch (_: Exception) {}
            }
        }
    }

    private fun startSender() = thread(isDaemon = true) {
        while (true) {
            val o = out; val f = queue.poll()
            if (o != null && f != null) {
                try { o.write(f); o.flush() } catch (_: Exception) {}
            } else {
                try { Thread.sleep(1) } catch (_: Exception) {}
            }
        }
    }

    /** Desen adini yerellestirilmis olarak dondurur. */
    private fun patternLabel(): String {
        val name = when (view.pattern) {
            AreaView.Pattern.GRID -> getString(R.string.pattern_grid)
            AreaView.Pattern.DOTS -> getString(R.string.pattern_dots)
            AreaView.Pattern.NONE -> getString(R.string.pattern_none)
        }
        return getString(R.string.pattern_label, name)
    }

    private fun amoledLabel(): String =
        if (view.amoled) getString(R.string.amoled_on) else getString(R.string.amoled_off)

    private fun setStatus(s: String) {
        // Ag thread'i panel kurulmadan once cagirabilir -> lateinit korumasi
        if (!::statusText.isInitialized) return
        ui.post { if (::statusText.isInitialized) statusText.text = s }
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
