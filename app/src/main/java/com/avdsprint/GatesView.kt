package com.avdsprint

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Puertas virtuales sobre la vista previa de la cámara, como Metric Sprint.
 * Cada puerta es una línea entre (xt, top) y (xb, bot) en fracciones de la imagen; se arrastra entera
 * desde su círculo de distancia o se inclina desde sus extremos para seguir la perspectiva de los conos.
 * Las posiciones vuelven a la página, que detecta el paso del deportista por cada puerta en el vídeo grabado.
 */
@SuppressLint("ViewConstructor")
class GatesView(ctx: Context, private val target: View) : View(ctx) {

    data class Gate(val d: Double, var xt: Float, var xb: Float)

    val gates = mutableListOf<Gate>()
    var top = 0.2f
    var bot = 0.92f

    private val dens = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD200.toInt(); strokeWidth = 3 * dens; style = Paint.Style.STROKE }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD200.toInt() }
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xEB0D6E46.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2 * dens }
    private val shade = Paint().apply { color = 0x47000000 }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 14 * dens; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 15 * dens; textAlign = Paint.Align.CENTER; setShadowLayer(6f, 0f, 0f, Color.BLACK)
    }

    private var drag: Int = -1
    private var dragKind = 'm'
    private var downX = 0f
    private var g0xt = 0f
    private var g0xb = 0f

    companion object {
        fun fromJson(ctx: Context, json: String, target: View): GatesView? = try {
            val o = JSONObject(json)
            val v = GatesView(ctx, target)
            v.top = o.optDouble("top", 0.2).toFloat()
            v.bot = o.optDouble("bot", 0.92).toFloat()
            val a = o.getJSONArray("gates")
            for (i in 0 until a.length()) {
                val g = a.getJSONObject(i)
                v.gates.add(Gate(g.getDouble("d"), g.getDouble("xt").toFloat(), g.getDouble("xb").toFloat()))
            }
            if (v.gates.isEmpty()) null else v
        } catch (_: Exception) {
            null
        }
    }

    fun toJson(): String {
        val a = JSONArray()
        gates.forEach { a.put(JSONObject().put("d", it.d).put("xt", it.xt.toDouble()).put("xb", it.xb.toDouble())) }
        return JSONObject().put("gates", a).put("top", top.toDouble()).put("bot", bot.toDouble()).toString()
    }

    // Rectángulo donde se ve la imagen de la cámara (la vista previa conserva la proporción del vídeo).
    private fun ix() = target.x
    private fun iy() = target.y
    private fun iw() = target.width.toFloat().coerceAtLeast(1f)
    private fun ih() = target.height.toFloat().coerceAtLeast(1f)

    private fun dLabel(d: Double) = if (d % 1.0 == 0.0) "${d.toInt()} m" else "${"%.1f".format(d)} m"

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val x0 = ix(); val y0 = iy(); val w = iw(); val h = ih()
        val yt = y0 + top * h
        val yb = y0 + bot * h
        canvas.drawRect(x0, y0, x0 + w, yt, shade)
        canvas.drawRect(x0, yb, x0 + w, y0 + h, shade)
        val r = 22 * dens
        for (g in gates) {
            val a = x0 + g.xt * w
            val b = x0 + g.xb * w
            canvas.drawLine(a, yt, b, yb, line)
            canvas.drawCircle(a, yt, 7 * dens, dot)
            canvas.drawCircle(b, yb, 7 * dens, dot)
            val cx = (a + b) / 2
            val cy = yb - 34 * dens
            canvas.drawCircle(cx, cy, r, badge)
            canvas.drawCircle(cx, cy, r, ring)
            canvas.drawText(dLabel(g.d), cx, cy + 5 * dens, label)
        }
        canvas.drawText("Alinea cada puerta con su cono · arrastra el círculo o los extremos", x0 + w / 2, y0 + 26 * dens, hint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x0 = ix(); val y0 = iy(); val w = iw(); val h = ih()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                var best = -1; var kind = 'm'; var bd = 44 * dens
                gates.forEachIndexed { i, g ->
                    val a = x0 + g.xt * w; val b = x0 + g.xb * w
                    val pts = listOf(Triple('t', a, y0 + top * h), Triple('b', b, y0 + bot * h), Triple('m', (a + b) / 2, y0 + bot * h - 34 * dens))
                    for ((k, px, py) in pts) {
                        val d = hypot(px - e.x, py - e.y)
                        if (d < bd) { bd = d; best = i; kind = k }
                    }
                }
                if (best < 0) {
                    val yn = (e.y - y0) / h
                    if (yn in top..bot) gates.forEachIndexed { i, g ->
                        val gx = x0 + (g.xt + (g.xb - g.xt) * (yn - top) / (bot - top)) * w
                        val d = abs(gx - e.x)
                        if (d < bd) { bd = d; best = i; kind = 'm' }
                    }
                }
                if (best < 0) return false
                drag = best; dragKind = kind; downX = e.x
                g0xt = gates[best].xt; g0xb = gates[best].xb
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (drag < 0) return false
                val g = gates[drag]
                val fx = ((e.x - x0) / w).coerceIn(0.001f, 0.999f)
                val dx = (e.x - downX) / w
                when (dragKind) {
                    't' -> g.xt = fx
                    'b' -> g.xb = fx
                    else -> { g.xt = (g0xt + dx).coerceIn(0.001f, 0.999f); g.xb = (g0xb + dx).coerceIn(0.001f, 0.999f) }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val was = drag >= 0
                drag = -1
                return was
            }
        }
        return false
    }
}
