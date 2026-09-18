package ir.meduhelper.flow

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), FlowEngine.Host {

    private lateinit var webView: WebView
    private lateinit var engine: FlowEngine
    private lateinit var prefs: android.content.SharedPreferences

    private lateinit var guideCard: View
    private lateinit var guideStep: TextView
    private lateinit var guideText: TextView

    private var currentGuideKey: String = ""
    private var hiddenGuideKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("msh", MODE_PRIVATE)
        webView = findViewById(R.id.web)

        guideCard = findViewById(R.id.guideCard)
        guideStep = findViewById(R.id.guideStep)
        guideText = findViewById(R.id.guideText)
        findViewById<TextView>(R.id.guideClose).setOnClickListener {
            hiddenGuideKey = currentGuideKey
            guideCard.visibility = View.GONE
        }

        setupWebView()

        engine = FlowEngine(this, webView, prefs)
        webView.addJavascriptInterface(Bridge(), "MshBridge")

        findViewById<TextView>(R.id.debugFab).apply {
            setOnClickListener { ExtractionTool.run(webView, this@MainActivity) }
            setOnLongClickListener { confirmClearSession(); true }
        }

        OtpBus.set { code -> engine.fillOtp(code) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        if (savedInstanceState == null) {
            webView.loadUrl("https://my.medu.ir/")
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    private fun setupWebView() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
        }
        // کوکی‌های مشترک بین سه دامنه — حیاتی برای عبور SSO بدون لاگین مجدد
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.settings.setSupportMultipleWindows(false)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                return if (url.startsWith("http://") || url.startsWith("https://")) {
                    false
                } else {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) {}
                    true
                }
            }
            override fun onPageFinished(view: WebView, url: String) {
                CookieManager.getInstance().flush()
            }
        }
        webView.webChromeClient = WebChromeClient()
        WebView.setWebContentsDebuggingEnabled(true)
    }

    // ---------- FlowEngine.Host ----------
    override fun onGuide(stateKey: String, visible: Boolean, step: String?, message: String) {
        currentGuideKey = stateKey
        if (!visible || message.isEmpty()) {
            guideCard.visibility = View.GONE
            return
        }
        if (stateKey == hiddenGuideKey) return // کاربر برای همین مرحله بسته است
        guideCard.visibility = View.VISIBLE
        guideStep.visibility = if (step == null) View.GONE else View.VISIBLE
        guideStep.text = step ?: ""
        guideText.text = message
    }

    override fun requestSmsPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECEIVE_SMS), 101)
        }
    }

    override fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            toast("اجازه پیامک داده نشد — کد را دستی وارد کنید")
        }
    }

    override fun onResume() {
        super.onResume(); webView.onResume(); engine.start()
    }

    override fun onPause() {
        engine.stop(); CookieManager.getInstance().flush(); webView.onPause(); super.onPause()
    }

    // ---------- پل جاوااسکریپت (فقط خواندن؛ هیچ کلیک ساختگی) ----------
    inner class Bridge {
        @android.webkit.JavascriptInterface
        fun onPhoneSubmitted(v: String) {
            prefs.edit().putString("phone", v.trim()).apply()
        }
        @android.webkit.JavascriptInterface
        fun onSchoolClicked(v: String) {
            engine.saveSchool(v)
        }
    }

    private fun confirmClearSession() {
        AlertDialog.Builder(this)
            .setTitle("پاک کردن داده‌ها")
            .setMessage("نشست، شماره و مدرسه ذخیره‌شده پاک شود؟")
            .setPositiveButton("بله") { _, _ ->
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                prefs.edit().clear().apply()
                webView.reload()
                toast("پاک شد")
            }
            .setNegativeButton("انصراف", null)
            .show()
    }
}
