package com.deku.penlet

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

/**
 * penlet — S Pen -> PC tablet kopru istemcisi.
 *
 * Tum ekrani bir yakalama yuzeyi yapar. S Pen (TOOL_TYPE_STYLUS) hem
 * dokununca (onTouchEvent) hem de havadayken (onGenericMotionEvent / HOVER)
 * X/Y gonderir. osu!'da aim icin hover sart.
 *
 * Protokol: 5-byte frame, big-endian [uint16 x][uint16 y][uint8 flags]
 *   x,y  : 0..32767 araligina normalize (ham cozunurluk)
 *   flags: bit0 = pen down (ekrana degdi)
 *
 * Baglanti: 127.0.0.1:40118  ->  'adb reverse tcp:40118 tcp:40118' ile PC'ye tunellenir.
 * TCP_NODELAY acik (Nagle kapali) — latency icin sart.
 */
class MainActivity : AppCompatActivity() {

    private val host = "127.0.0.1"
    private val port = 40118
    private val axisMax = 32767

    @Volatile private var out: OutputStream? = null
    @Volatile private var connected = false
    private val queue = ConcurrentLinkedQueue<ByteArray>()
    private lateinit var status: TextView
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ekran kapanmasin — oyun boyunca acik kalmali
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        status = TextView(this).apply {
            textSize = 18f
            setPadding(40, 80, 40, 40)
            text = "penlet\n\nBaglaniyor 127.0.0.1:$port ...\n\n" +
                   "PC'de:\n  adb reverse tcp:$port tcp:$port\n  sudo ./penlet\n\n" +
                   "Kalemi ekranda gezdir."
        }

        val surface = object : View(this) {
            override fun onTouchEvent(e: MotionEvent): Boolean { handle(e); return true }
            override fun onGenericMotionEvent(e: MotionEvent): Boolean { handle(e); return true }
        }
        // FrameLayout: altta yakalama yuzeyi, ustte durum yazisi
        val root = android.widget.FrameLayout(this)
        root.addView(surface, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(status, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
        setContentView(root)

        startNetworkThread()
        startSenderThread()
    }

    private fun setStatus(s: String) { ui.post { status.text = s } }

    private fun startNetworkThread() = thread(isDaemon = true) {
        while (true) {
            try {
                val sock = Socket()
                sock.tcpNoDelay = true          // <-- Nagle kapali, kritik
                sock.connect(InetSocketAddress(host, port), 3000)
                out = sock.getOutputStream()
                connected = true
                setStatus("BAGLANDI ✓  Kalemi gezdir.\n(osu! area'yi kalibre etmeyi unutma)")
                // baglanti canli kaldigi surece bekle
                val buf = ByteArray(1)
                while (true) {
                    // karsi taraf kapanirsa read -1 doner
                    val r = sock.getInputStream().read(buf)
                    if (r < 0) break
                }
            } catch (ex: Exception) {
                setStatus("Baglanti yok: ${ex.message}\nadb reverse + daemon calisiyor mu?\nTekrar deneniyor...")
            } finally {
                connected = false
                out = null
                try { Thread.sleep(1000) } catch (_: Exception) {}
            }
        }
    }

    // Ayri gonderim thread'i: dokunma callback'ini bloklamadan yaz
    private fun startSenderThread() = thread(isDaemon = true) {
        while (true) {
            val o = out
            val frame = queue.poll()
            if (o != null && frame != null) {
                try { o.write(frame); o.flush() } catch (_: Exception) {}
            } else {
                try { Thread.sleep(1) } catch (_: Exception) {}
            }
        }
    }

    private var vw = 0
    private var vh = 0

    private fun handle(e: MotionEvent) {
        // Sadece S Pen; parmagi yok say (yanlislikla dokunma osu!'yu bozmasin)
        if (e.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return
        if (vw == 0) { vw = window.decorView.width; vh = window.decorView.height }
        if (vw == 0 || vh == 0) return

        val nx = (e.x / vw).coerceIn(0f, 1f)
        val ny = (e.y / vh).coerceIn(0f, 1f)
        val xi = (nx * axisMax).toInt()
        val yi = (ny * axisMax).toInt()

        val down = when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> false
            MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_HOVER_MOVE,
            MotionEvent.ACTION_HOVER_EXIT -> false
            else -> false
        }

        val flags = if (down) 0x01 else 0x00
        val frame = byteArrayOf(
            ((xi shr 8) and 0xFF).toByte(), (xi and 0xFF).toByte(),
            ((yi shr 8) and 0xFF).toByte(), (yi and 0xFF).toByte(),
            (flags and 0xFF).toByte()
        )
        // kuyruk sismesin — cok gerilerse eskiyi at (en guncel konum onemli)
        if (queue.size > 64) queue.clear()
        queue.add(frame)
    }
}
