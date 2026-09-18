package ir.meduhelper.flow

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.webkit.WebView
import android.widget.ScrollView
import android.widget.TextView

object ExtractionTool {

    fun run(webView: WebView, activity: Context) {
        webView.evaluateJavascript(EXTRACT_JS) { raw ->
            val json = raw ?: "empty"
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("json", json))

            val tv = TextView(activity).apply {
                text = json; setPadding(40, 24, 40, 24); setTextIsSelectable(true); textSize = 11f
            }
            AlertDialog.Builder(activity)
                .setTitle("JSON صفحه (در حافظه کپی شد)")
                .setView(ScrollView(activity).apply { addView(tv) })
                .setPositiveButton("اشتراک‌گذاری") { _, _ ->
                    activity.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, json)
                            }, "ارسال JSON"
                        )
                    )
                }
                .setNegativeButton("بستن", null)
                .show()
        }
    }

    private val EXTRACT_JS = """(function(){
  var out={url:location.href,title:document.title,controls:[],clickables:[],images:[]};
  var txt=function(el){return (el.innerText||el.value||el.placeholder||'').trim().replace(/\s+/g,' ').slice(0,60);};
  var sel=function(el){
    if(el.id) return '#'+el.id;
    var t=el.tagName.toLowerCase();
    if(el.name) return t+'[name="'+el.name+'"]';
    return t+[].slice.call(el.classList,0,2).map(function(c){return '.'+c;}).join('');
  };
  [].forEach.call(document.querySelectorAll('input,select,textarea'),function(el){
    out.controls.push({sel:sel(el),type:el.type||'',name:el.name||'',id:el.id||'',
      placeholder:el.placeholder||'',label:txt(el.closest('label')||el.parentElement)});
  });
  [].forEach.call(document.querySelectorAll('a,button,[role="button"]'),function(el){
    out.clickables.push({sel:sel(el),tag:el.tagName.toLowerCase(),text:txt(el),
      href:el.getAttribute('href')||''});
  });
  [].forEach.call(document.querySelectorAll('img'),function(el){
    var s=el.currentSrc||el.src||'';
    if(/captcha|code|security|verify/i.test(s+el.id+el.className))
      out.images.push({sel:sel(el),src:s.slice(0,150)});
  });
  return out;
})()""".trimIndent()
}