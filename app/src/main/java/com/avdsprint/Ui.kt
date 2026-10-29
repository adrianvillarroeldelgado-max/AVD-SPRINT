package com.avdsprint

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.TextView
import java.io.File

/** Paleta AVD: azul marino, naranja de tartán y gris azulado (igual que AVD jump). */
object Col {
    const val BG = 0xFFEEF1F5.toInt()
    const val SURFACE = 0xFFFFFFFF.toInt()
    const val INK = 0xFF152033.toInt()
    const val MUTED = 0xFF5B6678.toInt()
    const val LINE = 0xFFD6DCE5.toInt()
    const val ACCENT = 0xFFC63F18.toInt()
    const val TRACK = 0xFF1E4F8C.toInt()
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.text(s: String, sizeSp: Float = 15f, color: Int = Col.INK, bold: Boolean = false): TextView =
    TextView(this).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }

fun Context.rounded(fill: Int, stroke: Int = 0, radiusDp: Int = 10): GradientDrawable = GradientDrawable().apply {
    setColor(fill)
    cornerRadius = dp(radiusDp).toFloat()
    if (stroke != 0) setStroke(dp(1), stroke)
}

fun Context.button(label: String, primary: Boolean = false, onClick: () -> Unit): Button = Button(this).apply {
    text = label
    isAllCaps = false
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
    typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC)
    setTextColor(if (primary) 0xFFFFFFFF.toInt() else Col.INK)
    background = rounded(if (primary) Col.ACCENT else Col.SURFACE, if (primary) Col.ACCENT else Col.LINE, 8)
    stateListAnimator = null
    minHeight = dp(52)
    setPadding(dp(18), dp(8), dp(18), dp(8))
    setOnClickListener { onClick() }
}

/** Carpeta privada de los vídeos grabados con la app. */
object Videos {
    fun dir(ctx: Context): File =
        (ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES) ?: File(ctx.filesDir, "videos")).apply { mkdirs() }

    /** Borra las grabaciones de más de 7 días para no llenar el teléfono. */
    fun cleanOld(ctx: Context) {
        val old = System.currentTimeMillis() - 7L * 24 * 3600_000L
        dir(ctx).listFiles()?.forEach { if (it.lastModified() < old) it.delete() }
    }
}

/**
 * Base de las pantallas. Desde Android 15 (obligatorio al apuntar a Android 16) las apps dibujan
 * detrás de las barras del sistema: aquí se agrega a la vista raíz el margen de las barras,
 * del recorte de cámara y del teclado.
 */
open class BaseActivity : Activity() {

    /** true en pantallas oscuras (cámara): iconos claros en las barras. */
    protected open val darkSystemBars = false

    override fun setContentView(view: View) {
        super.setContentView(view)
        fitSystemBars(view)
    }

    @Suppress("DEPRECATION")
    private fun fitSystemBars(view: View) {
        if (Build.VERSION.SDK_INT < 30) return
        window.setDecorFitsSystemWindows(false)
        if (Build.VERSION.SDK_INT < 35) {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
        val l = view.paddingLeft
        val t = view.paddingTop
        val r = view.paddingRight
        val b = view.paddingBottom
        view.setOnApplyWindowInsetsListener { v, insets ->
            val i = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
            )
            v.setPadding(l + i.left, t + i.top, r + i.right, b + i.bottom)
            insets
        }
        view.requestApplyInsets()
        val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (darkSystemBars) 0 else light, light)
    }
}
