package com.deku.penlet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * penlet — area secim + girdi yakalama yuzeyi.
 *
 * IKI MOD:
 *  - SETUP : area'yi kalemle/parmakla ayarlarsin. Girdi PC'ye GITMEZ.
 *  - ACTIVE: area kilitli. Sadece area icindeki S Pen girdisi PC'ye gider.
 *
 * SETUP modunda üc yontem birden aktif:
 *  1) Bos alana surukle  -> yeni dikdortgen ciz
 *  2) Area icine surukle -> area'yi tasi
 *  3) Kose tutamaci surukle -> boyutlandir
 *  (Sayisal giris ayri: MainActivity'deki alanlar)
 */
class AreaView(context: Context) : View(context) {

    enum class Mode { SETUP, ACTIVE }
    enum class Pattern { GRID, DOTS, NONE }

    // --- durum ---
    var mode = Mode.SETUP
        set(v) { field = v; invalidate() }

    var pattern = Pattern.GRID
        set(v) { field = v; invalidate() }

    var amoled = true
        set(v) { field = v; invalidate() }

    /** Arka plan gorseli (sadece gorsel amacli, opsiyonel). */
    var bgImage: Bitmap? = null
        set(v) { field = v; invalidate() }

    var backgroundAlpha = 60   // 0..255
        set(v) { field = v.coerceIn(0, 255); invalidate() }

    /** Area, 0..1 normalize (ekrana gore). */
    var area = RectF(0f, 0f, 1f, 1f)
        private set

    /** Area degisince haber ver (kaydetmek + sayisal alanlari guncellemek icin). */
    var onAreaChanged: ((RectF) -> Unit)? = null

    /** ACTIVE modda area icindeki S Pen girdisi. (nx, ny normalize AREA ICINDE, down) */
    var onPenInput: ((Float, Float, Boolean) -> Unit)? = null

    // --- surukleme durumu ---
    private enum class Drag { NONE, NEW, MOVE, TL, TR, BL, BR }
    private var drag = Drag.NONE
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var startArea = RectF()

    private val handleR = 44f      // kose tutamaci yaricapi (dokunma toleransi)

    // --- boyalar ---
    private val pBg      = Paint().apply { style = Paint.Style.FILL }
    private val pPattern = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f }
    private val pAreaFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pAreaLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val pHandle  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pText    = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 32f }
    private val pBmp     = Paint(Paint.FILTER_BITMAP_FLAG)

    // --- renkler (AMOLED / normal) ---
    private val bgColor      get() = if (amoled) Color.BLACK else Color.rgb(18, 18, 20)
    private val patternColor get() = if (amoled) Color.rgb(26, 26, 30) else Color.rgb(38, 38, 44)
    private val accent       get() = Color.rgb(90, 170, 255)
    private val accentDim    get() = Color.argb(46, 90, 170, 255)
    private val textColor    get() = Color.rgb(150, 150, 158)

    fun setArea(r: RectF) {
        area.set(
            r.left.coerceIn(0f, 1f), r.top.coerceIn(0f, 1f),
            r.right.coerceIn(0f, 1f), r.bottom.coerceIn(0f, 1f)
        )
        normalizeArea()
        invalidate()
        onAreaChanged?.invoke(RectF(area))
    }

    private fun normalizeArea() {
        val l = min(area.left, area.right); val r = max(area.left, area.right)
        val t = min(area.top, area.bottom); val b = max(area.top, area.bottom)
        // cok kucuk area'yi engelle (min %5)
        area.set(l, t, max(r, l + 0.05f).coerceAtMost(1f), max(b, t + 0.05f).coerceAtMost(1f))
    }

    // --- cizim ---
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()

        // zemin
        pBg.color = bgColor
        c.drawRect(0f, 0f, w, h, pBg)

        // arka plan gorseli (sadece gorsel)
        bgImage?.let { bmp ->
            pBmp.alpha = backgroundAlpha
            c.drawBitmap(bmp, null, Rect(0, 0, width, height), pBmp)
        }

        // desen
        drawPattern(c, w, h)

        // area
        val ar = RectF(area.left * w, area.top * h, area.right * w, area.bottom * h)

        if (mode == Mode.SETUP) {
            pAreaFill.color = accentDim
            c.drawRect(ar, pAreaFill)
            pAreaLine.color = accent
            c.drawRect(ar, pAreaLine)

            // kose tutamaclari
            pHandle.color = accent
            val r = 14f
            c.drawCircle(ar.left,  ar.top,    r, pHandle)
            c.drawCircle(ar.right, ar.top,    r, pHandle)
            c.drawCircle(ar.left,  ar.bottom, r, pHandle)
            c.drawCircle(ar.right, ar.bottom, r, pHandle)

            // olcu yazisi
            pText.color = textColor
            // Locale.US: bazi dillerde ondalik ayraci farkli — tutarli gorunsun
            val pct = String.format(java.util.Locale.US, "%.0f%% x %.0f%%",
                area.width() * 100, area.height() * 100)
            c.drawText(pct, ar.left + 12f, ar.top - 14f, pText)
        } else {
            // ACTIVE: sade cerceve, dikkat dagitmasin
            pAreaLine.color = Color.rgb(40, 90, 130)
            pAreaLine.strokeWidth = 2f
            c.drawRect(ar, pAreaLine)
            pAreaLine.strokeWidth = 3f
        }
    }

    private fun drawPattern(c: Canvas, w: Float, h: Float) {
        if (pattern == Pattern.NONE) return
        pPattern.color = patternColor
        val step = 48f
        when (pattern) {
            Pattern.GRID -> {
                pPattern.style = Paint.Style.STROKE
                var x = 0f
                while (x <= w) { c.drawLine(x, 0f, x, h, pPattern); x += step }
                var y = 0f
                while (y <= h) { c.drawLine(0f, y, w, y, pPattern); y += step }
            }
            Pattern.DOTS -> {
                pPattern.style = Paint.Style.FILL
                var y = step
                while (y < h) {
                    var x = step
                    while (x < w) { c.drawCircle(x, y, 2.5f, pPattern); x += step }
                    y += step
                }
            }
            Pattern.NONE -> {}
        }
    }

    // --- girdi ---
    override fun onTouchEvent(e: MotionEvent): Boolean = handle(e)
    override fun onGenericMotionEvent(e: MotionEvent): Boolean = handle(e)

    private fun handle(e: MotionEvent): Boolean {
        return if (mode == Mode.SETUP) handleSetup(e) else handleActive(e)
    }

    /** SETUP: area'yi ayarla. Girdi PC'ye GITMEZ. Parmak da kalem de calisir. */
    private fun handleSetup(e: MotionEvent): Boolean {
        val w = width.toFloat(); val h = height.toFloat()
        if (w == 0f || h == 0f) return true
        val x = e.x; val y = e.y
        val ar = RectF(area.left * w, area.top * h, area.right * w, area.bottom * h)

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = x; dragStartY = y
                startArea.set(area)
                drag = when {
                    near(x, y, ar.left,  ar.top)    -> Drag.TL
                    near(x, y, ar.right, ar.top)    -> Drag.TR
                    near(x, y, ar.left,  ar.bottom) -> Drag.BL
                    near(x, y, ar.right, ar.bottom) -> Drag.BR
                    ar.contains(x, y)               -> Drag.MOVE
                    else                            -> Drag.NEW
                }
                if (drag == Drag.NEW) {
                    // yeni dikdortgen: baslangic noktasi
                    area.set(x / w, y / h, x / w, y / h)
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val nx = (x / w).coerceIn(0f, 1f)
                val ny = (y / h).coerceIn(0f, 1f)
                val dx = (x - dragStartX) / w
                val dy = (y - dragStartY) / h
                when (drag) {
                    Drag.NEW  -> { area.right = nx; area.bottom = ny }
                    Drag.TL   -> { area.left = nx; area.top = ny }
                    Drag.TR   -> { area.right = nx; area.top = ny }
                    Drag.BL   -> { area.left = nx; area.bottom = ny }
                    Drag.BR   -> { area.right = nx; area.bottom = ny }
                    Drag.MOVE -> {
                        val wdt = startArea.width(); val hgt = startArea.height()
                        val l = (startArea.left + dx).coerceIn(0f, 1f - wdt)
                        val t = (startArea.top  + dy).coerceIn(0f, 1f - hgt)
                        area.set(l, t, l + wdt, t + hgt)
                    }
                    Drag.NONE -> {}
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (drag != Drag.NONE) {
                    normalizeArea()
                    drag = Drag.NONE
                    invalidate()
                    onAreaChanged?.invoke(RectF(area))
                }
            }
        }
        return true
    }

    /** ACTIVE: sadece S Pen + sadece area ici -> PC'ye gonder. */
    private fun handleActive(e: MotionEvent): Boolean {
        if (e.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return true
        val w = width.toFloat(); val h = height.toFloat()
        if (w == 0f || h == 0f) return true

        // ekran uzerinde normalize konum
        val sx = (e.x / w)
        val sy = (e.y / h)

        // area icinde mi? disindaysa yok say (kenar disi hareket cursor'u firlatmasin)
        if (sx < area.left || sx > area.right || sy < area.top || sy > area.bottom) return true

        // area ICINDE normalize et -> 0..1
        val aw = (area.width()).coerceAtLeast(1e-6f)
        val ah = (area.height()).coerceAtLeast(1e-6f)
        val nx = ((sx - area.left) / aw).coerceIn(0f, 1f)
        val ny = ((sy - area.top)  / ah).coerceIn(0f, 1f)

        val down = when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> true
            else -> false
        }
        onPenInput?.invoke(nx, ny, down)
        return true
    }

    private fun near(x: Float, y: Float, cx: Float, cy: Float) =
        abs(x - cx) < handleR && abs(y - cy) < handleR
}
