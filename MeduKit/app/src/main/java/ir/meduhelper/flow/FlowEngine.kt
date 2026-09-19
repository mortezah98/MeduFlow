package ir.meduhelper.flow

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import org.json.JSONObject

class FlowEngine(
    private val host: Host,
    private val webView: WebView,
    private val prefs: SharedPreferences
) {
    interface Host {
        fun onGuide(stateKey: String, visible: Boolean, step: String?, message: String)
        fun requestSmsPermission()
        fun toast(msg: String)
    }

    enum class St(val stepLabel: String?, val message: String) {
        HOME("مرحله ۱ از ۶", "دکمهٔ «ورود به صفحه شخصی» را لمس کنید."),
        SSO_LOGIN("مرحله ۲ از ۶", "کد امنیتی داخل تصویر را بنویسید و «ارسال کد» را بزنید."),
        SSO_CHALLENGE("مرحله ۲ از ۶", "این مرحله کپچای تعاملی است — لطفاً خودتان کامل کنید."),
        SSO_OTP("مرحله ۳ از ۶", "کد ۵ رقمی پیامک‌شده به‌صورت خودکار وارد می‌شود؛ اگر نیامد، دستی بنویسید و تایید را بزنید."),
        PORTAL_LOGGED(null, "ورود موفق ✔ در حال انتقال به سامانهٔ نماد…"),
        NEED_LOGIN(null, "در حال هدایت به صفحه ورود…"),
        MANUAL_NAMAD("مرحله ۴ از ۶", "انتقال خودکار انجام نشد. در همین صفحه آیکون «نماد» را پیدا و لمس کنید."),
        NAMAD_ACCOUNTS("مرحله ۴ از ۶", "کارت مدرسهٔ فرزندتان را پیدا کنید و «ورود به عنوان والدین» را بزنید."),
        NAMAD_DASHBOARD("مرحله ۵ از ۶", "روی آیکون «آزمون» بزنید."),
        NAMAD_TEST_LIST("مرحله ۶ از ۶", "آزمون موردنظر را از فهرست انتخاب کنید. (پایان نسخهٔ فعلی)"),
        UNKNOWN(null, "")
    }

    var state: St = St.UNKNOWN
        private set

    private var lastAppliedUrl: String? = null
    private var jumpCount = 0
    private var jumpPending = false
    private var otpAsked = false
    private var tickCount = 0
    private var manualMode = false
    private var ssoCompleted = false
    private var namadSince = 0L
    private var needLoginNav = false
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            tickCount++
            probeAndAct(tickCount % 3 == 0)
            handler.postDelayed(this, 700)
        }
    }

    fun start() { if (!running) { running = true; handler.post(tick) } }
    fun stop() { running = false; handler.removeCallbacks(tick) }

    fun savedPhone(): String = prefs.getString("phone", "") ?: ""
    fun savedSchool(): String = prefs.getString("school", "") ?: ""

    fun saveSchool(name: String) {
        val n = name.trim().take(60)
        if (n.length < 2) return
        prefs.edit().putString("school", n).apply()
    }

    private fun probeAndAct(force: Boolean) {
        val url = webView.url ?: return
        webView.evaluateJavascript(PROBE_JS) { raw ->
            if (raw == null || raw == "null") return@evaluateJavascript
            val p = try { JSONObject(raw) } catch (e: Exception) { return@evaluateJavascript }
            val st = detect(url, p)

            // نشانه‌های جریان واقعی ورود SSO
            when (st) {
                St.SSO_OTP -> ssoCompleted = true
                St.SSO_LOGIN, St.SSO_CHALLENGE -> ssoCompleted = false
                else -> {}
            }

            // نماد با موفقیت و با محتوا لود شد → خروج از حالت دستی
            if (st == St.NAMAD_ACCOUNTS && p.optBoolean("schoolCards")) {
                manualMode = false
                namadSince = 0
            }

            // صفحهٔ نماد بیش از ۲۰ ثانیه خالی ماند → بازگشت به روش مطمئن
            if (st == St.NAMAD_ACCOUNTS) {
                if (namadSince == 0L) namadSince = System.currentTimeMillis()
                if (!manualMode && !p.optBoolean("schoolCards") &&
                    System.currentTimeMillis() - namadSince > 20000
                ) {
                    manualMode = true
                    namadSince = 0
                    host.toast("بارگذاری خودکار نماد ناموفق بود — بازگشت به روش دستی")
                    webView.loadUrl(
                        if (ssoCompleted) "https://my.medu.ir/"
                        else "https://my.medu.ir/login.html"
                    )
                    return@evaluateJavascript
                }
            }

            val entered = st != state
            if (entered || url != lastAppliedUrl || force) {
                state = st
                lastAppliedUrl = url
                applyState(st, url)
            }
        }
    }

    private fun detect(url: String, p: JSONObject): St {
        val h = hostOf(url)
        return when {
            h.endsWith("my.medu.ir") && url.contains("login.html")   -> St.HOME
            h == "sso.my.gov.ir" && p.optBoolean("otp")              -> St.SSO_OTP
            h == "sso.my.gov.ir" && p.optBoolean("mobile")           -> St.SSO_LOGIN
            h == "sso.my.gov.ir"                                     -> St.SSO_CHALLENGE
            h == "namad.medu.ir" && url.contains("my-accounts")      -> St.NAMAD_ACCOUNTS
            h == "namad.medu.ir" && url.contains("advisory-test")    -> St.NAMAD_TEST_LIST
            h == "namad.medu.ir" && url.contains("/dashboard/")      -> St.NAMAD_DASHBOARD
            h.endsWith("my.medu.ir") -> when {
                manualMode   -> St.MANUAL_NAMAD
                ssoCompleted -> St.PORTAL_LOGGED
                else         -> St.NEED_LOGIN
            }
            else -> St.UNKNOWN
        }
    }

    private fun hostOf(url: String): String =
        try { android.net.Uri.parse(url).host ?: "" } catch (e: Exception) { "" }

    private fun applyState(st: St, url: String) {
        when (st) {
            St.UNKNOWN -> {
                host.onGuide(st.name, false, null, "")
                run(PRELUDE)
            }
            St.HOME -> {
                host.onGuide(st.name, true, st.stepLabel, st.message)
                run(PRELUDE + HOME_JS)
            }
            St.SSO_LOGIN -> {
                val msg = if (savedPhone().isNotEmpty())
                    "شمارهٔ شما خودکار پر شد ✔ فقط کد امنیتی تصویر را بنویسید و «ارسال کد» را بزنید. اگر تصویر ناخوانا بود دکمهٔ ♻ را بزنید."
                else
                    "شمارهٔ همراه و کد امنیتی تصویر را وارد کنید و «ارسال کد» را بزنید. (دفعات بعد شماره خودکار پر می‌شود)"
                host.onGuide(st.name, true, st.stepLabel, msg)
                run(PRELUDE + SSO_LOGIN_JS, savedPhone())
            }
            St.SSO_CHALLENGE -> {
                host.onGuide(st.name, true, st.stepLabel, st.message)
                run(PRELUDE)
            }
            St.SSO_OTP -> {
                if (!otpAsked) { otpAsked = true; host.requestSmsPermission() }
                host.onGuide(st.name, true, st.stepLabel, st.message)
                run(PRELUDE + OTP_HIGHLIGHT_JS)
            }
            St.PORTAL_LOGGED -> {
                val msg = if (jumpCount >= 2)
                    "انتقال خودکار انجام نشد — لطفاً از همین صفحه کارت یا آیکون «نماد» را پیدا و لمس کنید."
                else st.message
                host.onGuide(st.name, true, null, msg)
                run(PRELUDE)
                maybeJumpToNamad()
            }
            St.NEED_LOGIN -> {
                host.onGuide(st.name, true, null, st.message)
                run(PRELUDE)
                if (!needLoginNav) {
                    needLoginNav = true
                    handler.postDelayed({
                        needLoginNav = false
                        if (state == St.NEED_LOGIN) webView.loadUrl("https://my.medu.ir/login.html")
                    }, 1000)
                }
            }
            St.MANUAL_NAMAD -> {
                host.onGuide(st.name, true, st.stepLabel, st.message)
                run(PRELUDE + MANUAL_NAMAD_JS)
            }
            St.NAMAD_ACCOUNTS -> {
                val school = savedSchool()
                val msg = if (school.isNotEmpty())
                    st.message + " (دفعات قبل: " + school + " — با خط نارنجی مشخص شده)"
                else st.message
                host.onGuide(st.name, true, st.stepLabel, msg)
                run(PRELUDE + NAMAD_ACCOUNTS_JS, school)
            }
            St.NAMAD_DASHBOARD -> {
                host.onGuide(st.name, true, st.stepLabel, st.message)
                run(PRELUDE + NAMAD_DASHBOARD_JS)
            }
            St.NAMAD_TEST_LIST -> {
                host.onGuide(st.name, true, st.stepLabel, st.message)
                run(PRELUDE)
            }
        }
    }

    private fun maybeJumpToNamad() {
        if (manualMode || jumpCount >= 2 || jumpPending) return
        jumpPending = true
        handler.postDelayed({
            jumpPending = false
            if (state == St.PORTAL_LOGGED) {
                jumpCount++
                webView.loadUrl("https://namad.medu.ir/my-accounts")
            }
        }, 1500)
    }

    fun fillOtp(code: String) {
        if (state != St.SSO_OTP) return
        val call = "(" + OTP_FILL_JS + ")(" + JSONObject.quote(code) + ");"
        webView.evaluateJavascript(call) { res ->
            val ok = res?.contains("OK") == true
            host.onGuide(
                St.SSO_OTP.name, true, St.SSO_OTP.stepLabel,
                if (ok) "کد پیامکی وارد شد ✔ دکمهٔ تایید (شمارندهٔ زمان) را بزنید."
                else "کد را دستی وارد کنید و تایید را بزنید."
            )
        }
    }

    private fun run(js: String, arg: String? = null) {
        val call = if (arg != null)
            "(function(a){ var saved=a; " + js + " })(" + JSONObject.quote(arg) + ");"
        else js
        webView.evaluateJavascript(call, null)
    }

    // ---------- جاوااسکریپت‌ها ----------
    private val PROBE_JS = """(function(){
  function vis(e){ if(!e) return false; var r=e.getBoundingClientRect();
    if(r.width<2||r.height<2) return false;
    var s=getComputedStyle(e); return s.visibility!=='hidden'&&s.display!=='none'; }
  function q(sel){ try{ return vis(document.querySelector(sel)); }catch(err){ return false; } }
  var otps=0;
  try{ otps=[].filter.call(document.querySelectorAll('input.otp-generator-input'),vis).length; }catch(e){}
  return {
    person:q('#person'),
    mobile:q('#mobile'),
    otp: otps>0,
    schoolCards: ((document.body&&document.body.innerText)||'').indexOf('ورود به عنوان والدین')>-1,
    testIcon: q('a[href*="advisory-test"]')
  };
})()""".trimIndent()

    private val PRELUDE = """function __mshStyle(){
  if(document.getElementById('msh-style')) return;
  var s=document.createElement('style'); s.id='msh-style';
  s.textContent='.msh-pulse{box-shadow:0 0 0 3px #16a34a!important;border-radius:14px!important;animation:mshP 1.5s infinite}.msh-ring{outline:3px solid #f59e0b!important;outline-offset:3px;border-radius:14px}.msh-hide{display:none!important}@keyframes mshP{0%,100%{box-shadow:0 0 0 3px rgba(22,163,74,.95)}50%{box-shadow:0 0 0 11px rgba(22,163,74,.18)}}';
  (document.head||document.documentElement).appendChild(s);
}
function __mshClear(){
  [].forEach.call(document.querySelectorAll('.msh-pulse,.msh-ring,.msh-hide'),
    function(e){ e.classList.remove('msh-pulse','msh-ring','msh-hide'); });
}
function __mshInnermost(txt){
  var all=[].slice.call(document.querySelectorAll('button,a,[role="button"],div,li,section,article'));
  var hit=all.filter(function(e){ var t=(e.innerText||'');
    return t.indexOf(txt)>-1 && t.length<400; });
  return hit.filter(function(e){ return !hit.some(function(o){ return o!==e&&e.contains(o); }); });
}
__mshStyle(); __mshClear();
""".trimIndent()

    private val HOME_JS = """(function(){
  var hide=function(s){ [].forEach.call(document.querySelectorAll(s),function(e){ e.classList.add('msh-hide'); }); };
  hide('#soha'); hide('#hghoghi');
  var p=document.querySelector('#person'); if(p) p.classList.add('msh-pulse');
})();""".trimIndent()

    private val SSO_LOGIN_JS = """(function(){
  var hide=function(s){ [].forEach.call(document.querySelectorAll(s),function(e){ e.classList.add('msh-hide'); }); };
  hide('a.linearBorder'); hide('button.login-with-qr-btn'); hide('button.login-with-app-btn');
  if(!document.getElementById('msh-cap')){
    var st=document.createElement('style'); st.id='msh-cap';
    st.textContent='img.captcha-image{transform:scale(1.5);transform-origin:top center;margin:18px 0}';
    (document.head||document.documentElement).appendChild(st);
  }
  var m=document.querySelector('#mobile');
  if(m && (m.value||'').length===0 && saved && saved.length>0){
    var setter=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;
    setter.call(m, saved);
    m.dispatchEvent(new Event('input',{bubbles:true}));
    m.dispatchEvent(new Event('change',{bubbles:true}));
  }
  var sb=document.querySelector('#submit-login-button'); if(sb) sb.classList.add('msh-pulse');
  var rc=document.querySelector('button.reset-captcha'); if(rc) rc.classList.add('msh-ring');
  var vc=document.querySelector('button.voice-captcha'); if(vc) vc.classList.add('msh-ring');
  if(!window.__mshPhoneHook){
    window.__mshPhoneHook=true;
    document.addEventListener('click', function(ev){
      var e=ev.target, hit=false;
      for(var i=0;i<6&&e;i++){ if(e.id==='submit-login-button'){hit=true;break;} e=e.parentElement; }
      if(hit){ var mm=document.querySelector('#mobile');
        if(mm&&window.MshBridge) window.MshBridge.onPhoneSubmitted(mm.value||''); }
    }, true);
  }
})();""".trimIndent()

    private val OTP_HIGHLIGHT_JS = """(function(){
  var b=document.querySelector('#otp-submit-btn'); if(b) b.classList.add('msh-pulse');
})();""".trimIndent()

    private val OTP_FILL_JS = """function(code){
  var vis=function(e){ var r=e.getBoundingClientRect(); return r.width>1&&r.height>1; };
  var inputs=[].filter.call(document.querySelectorAll('input.otp-generator-input'),vis);
  if(!inputs.length) return 'NO_INPUTS';
  var setter=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;
  var ds=code.split('');
  if(inputs.length===1){
    setter.call(inputs[0],code);
    inputs[0].dispatchEvent(new Event('input',{bubbles:true}));
    inputs[0].dispatchEvent(new Event('change',{bubbles:true}));
  } else {
    inputs.forEach(function(inp,i){
      setter.call(inp, ds[i]||'');
      inp.dispatchEvent(new Event('input',{bubbles:true}));
      inp.dispatchEvent(new Event('change',{bubbles:true}));
    });
  }
  var b=document.querySelector('#otp-submit-btn'); if(b) b.classList.add('msh-pulse');
  return 'OK:'+inputs.length;
}""".trimIndent()

    private val NAMAD_ACCOUNTS_JS = """(function(){
  __mshInnermost('ورود به عنوان والدین').forEach(function(e){ e.classList.add('msh-pulse'); });
  if(saved && saved.length>1){
    var prev=__mshInnermost(saved);
    if(prev.length>0){
      prev[0].classList.add('msh-ring');
      try{ prev[0].scrollIntoView({block:'center',behavior:'smooth'}); }catch(e){}
    }
  }
  if(!window.__mshSchoolHook){
    window.__mshSchoolHook=true;
    document.addEventListener('click', function(ev){
      var e=ev.target, btn=null;
      for(var i=0;i<7&&e;i++){
        var t=(e.innerText||'');
        if(t.indexOf('ورود به عنوان والدین')>-1 && t.length<60){ btn=e; break; }
        e=e.parentElement;
      }
      if(btn){
        var card=btn.parentElement;
        for(var j=0;j<6&&card;j++){
          var t2=(card.innerText||'');
          if(t2.length>=40 && t2.length<300) break;
          card=card.parentElement;
        }
        var lines=((card&&card.innerText)||btn.innerText||'').split('\n')
          .map(function(s){ return s.trim(); })
          .filter(function(s){ return s.length>1 && s.indexOf('ورود به عنوان')===-1; });
        if(window.MshBridge && lines.length>0) window.MshBridge.onSchoolClicked(lines[0]);
      }
    }, true);
  }
})();""".trimIndent()

    private val NAMAD_DASHBOARD_JS = """(function(){
  var t=document.querySelector('a[href*="advisory-test"]');
  if(t) t.classList.add('msh-pulse');
})();""".trimIndent()

    private val MANUAL_NAMAD_JS = """(function(){
  __mshInnermost('نماد').forEach(function(e){ e.classList.add('msh-pulse'); });
})();""".trimIndent()
}
