package com.avdsprint

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream

/**
 * AVD Sprint: la app web (perfil F-V, biomecánica, fotocélula, progreso e informes) corre dentro de un WebView
 * y funciona sin internet. El teléfono aporta lo que el navegador no puede: grabar en cámara lenta real
 * (240 fps, sesión de alta velocidad de Camera2) y guardar o compartir archivos (PDF, CSV, PNG, copias).
 *
 * La página se sirve desde https://appassets.androidplatform.net/assets/www/ para que tenga un origen seguro:
 * así funcionan el almacenamiento local, fetch() y los vídeos grabados (/videos/…).
 */
class MainActivity : BaseActivity() {

    companion object {
        private const val HOST = "appassets.androidplatform.net"
        private const val START = "https://$HOST/assets/www/index.html"
        private const val RQ_FILE = 1
        private const val RQ_RECORD = 2
        private const val RQ_CAM_PERM = 3
    }

    private lateinit var web: WebView
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var recordId: String? = null
    private var pendingPerm: PermissionRequest? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Videos.cleanOld(this)
        web = WebView(this)
        val root = FrameLayout(this).apply {
            setBackgroundColor(Col.BG)
            addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        setContentView(root)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = true
            textZoom = 100
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        web.addJavascriptInterface(Bridge(), "AVDNative")

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                serve(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == HOST) return false
                // Enlaces externos (fuentes, GitHub): se abren en el navegador del teléfono.
                try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams
            ): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = callback
                val types = params.acceptTypes.map { it.trim() }.filter { it.contains('/') }.distinct()
                val i = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(if (types.size == 1) types[0] else "*/*")
                if (types.size > 1) i.putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
                return try {
                    startActivityForResult(Intent.createChooser(i, "Elegir archivo"), RQ_FILE)
                    true
                } catch (_: Exception) {
                    fileCb = null
                    false
                }
            }

            // Cámara en vivo del navegador (respaldo): solo para la propia página.
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    if (request.origin.host != HOST) { request.deny(); return@runOnUiThread }
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(request.resources)
                    } else {
                        pendingPerm = request
                        requestPermissions(arrayOf(Manifest.permission.CAMERA), RQ_CAM_PERM)
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                goBack()
            }
        }

        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) web.loadUrl(START)
    }

    private fun goBack() {
        if (web.canGoBack()) web.goBack() else finish()
    }

    @Deprecated("Android 12 o anterior")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (Build.VERSION.SDK_INT >= 33) super.onBackPressed() else goBack()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        Updater.check(this)
    }

    override fun onPause() {
        web.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    // ---------- archivos de la app ----------

    private fun mime(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "html" -> "text/html"
        "js" -> "application/javascript"
        "css" -> "text/css"
        "ttf" -> "font/ttf"
        "png" -> "image/png"
        "svg" -> "image/svg+xml"
        "json" -> "application/json"
        "txt" -> "text/plain"
        "mp4" -> "video/mp4"
        else -> "application/octet-stream"
    }

    private fun notFound() = WebResourceResponse("text/plain", "UTF-8", 404, "No encontrado", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun serve(u: Uri): WebResourceResponse? {
        if (u.host != HOST) return null
        val path = u.path ?: return notFound()
        return try {
            when {
                path.startsWith("/assets/") -> {
                    val a = path.removePrefix("/assets/")
                    if (a.contains("..")) notFound()
                    else WebResourceResponse(mime(a), "UTF-8", assets.open(a))
                }
                path.startsWith("/videos/") -> {
                    val dir = Videos.dir(this).canonicalFile
                    val f = File(dir, path.removePrefix("/videos/")).canonicalFile
                    if (f.parentFile == dir && f.exists()) WebResourceResponse("video/mp4", null, FileInputStream(f)) else notFound()
                }
                else -> notFound()
            }
        } catch (_: Exception) {
            notFound()
        }
    }

    private fun js(code: String) = runOnUiThread { web.evaluateJavascript(code, null) }

    /** Guarda el archivo en Descargas/AVD Sprint (Android 10+) y abre el menú para compartirlo. */
    private fun saveAndShare(name: String, bytes: ByteArray) {
        val f = ShareFiles.newFile(this, name)
        f.writeBytes(bytes)
        var saved = false
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, ShareFiles.mime(f.name))
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/AVD Sprint")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    saved = true
                }
            } catch (_: Exception) {}
        }
        Toast.makeText(this, if (saved) "Guardado en Descargas › AVD Sprint" else "Archivo listo para compartir", Toast.LENGTH_LONG).show()
        try { ShareFiles.share(this, f, f.name) } catch (_: Exception) {}
    }

    /** Funciones que la página llama como window.AVDNative.… */
    inner class Bridge {
        @JavascriptInterface fun isApp(): Boolean = true
        @JavascriptInterface fun version(): String = BuildConfig.VERSION_NAME
        @JavascriptInterface fun canUpdate(): Boolean = Updater.enabled

        @JavascriptInterface fun checkUpdates() {
            runOnUiThread { Updater.check(this@MainActivity, true) }
        }

        @JavascriptInterface fun record(id: String) {
            runOnUiThread {
                recordId = id
                startActivityForResult(Intent(this@MainActivity, RecordActivity::class.java), RQ_RECORD)
            }
        }

        @JavascriptInterface fun saveFile(name: String, base64: String) {
            val bytes = try { Base64.decode(base64, Base64.DEFAULT) } catch (_: Exception) { null }
            runOnUiThread {
                if (bytes == null || bytes.isEmpty()) {
                    Toast.makeText(this@MainActivity, "No se pudo preparar el archivo.", Toast.LENGTH_LONG).show()
                } else {
                    saveAndShare(name, bytes)
                }
            }
        }
    }

    @Deprecated("API de resultados de Activity")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            RQ_FILE -> {
                val cb = fileCb
                fileCb = null
                val uris: Array<Uri>? = if (resultCode == RESULT_OK && data != null) {
                    data.clipData?.let { c -> Array(c.itemCount) { c.getItemAt(it).uri } } ?: data.data?.let { arrayOf(it) }
                } else null
                cb?.onReceiveValue(uris)
            }
            RQ_RECORD -> {
                val id = recordId ?: return
                recordId = null
                val path = data?.getStringExtra(RecordActivity.EX_FILE)
                if (resultCode == RESULT_OK && path != null) {
                    val name = File(path).name
                    val fps = data?.getIntExtra(RecordActivity.EX_FPS, 0) ?: 0
                    js("window.AVDNativeCb&&AVDNativeCb(${JSONObject.quote(id)},{url:'/videos/'+${JSONObject.quote(Uri.encode(name))},name:${JSONObject.quote(name)},fps:$fps})")
                } else {
                    js("window.AVDNativeCb&&AVDNativeCb(${JSONObject.quote(id)},null)")
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RQ_CAM_PERM) {
            val r = pendingPerm ?: return
            pendingPerm = null
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) r.grant(r.resources) else r.deny()
        }
    }
}
