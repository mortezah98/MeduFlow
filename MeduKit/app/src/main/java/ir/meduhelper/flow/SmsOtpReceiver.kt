package ir.meduhelper.flow

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Telephony

object OtpBus {
    private var listener: ((String) -> Unit)? = null
    fun set(l: (String) -> Unit) { listener = l }
    fun post(code: String) {
        Handler(Looper.getMainLooper()).post { listener?.invoke(code) }
    }
}

class SmsOtpReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val body = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            .joinToString("") { it.messageBody ?: "" }
        val code = extract(body) ?: return
        OtpBus.post(code)
    }

    private fun extract(raw: String): String? {
        // تبدیل ارقام فارسی/عربی به انگلیسی
        val fa = "۰۱۲۳۴۵۶۷۸۹"; val ar = "٠١٢٣٤٥٦٧٨٩"
        val sb = StringBuilder()
        raw.forEach { ch ->
            when {
                fa.indexOf(ch) >= 0 -> sb.append(fa.indexOf(ch))
                ar.indexOf(ch) >= 0 -> sb.append(ar.indexOf(ch))
                else -> sb.append(ch)
            }
        }
        val s = sb.toString()
        Regex("\\d{5}").find(s)?.let { return it.value }   // اولویت: کد ۵ رقمی
        return Regex("\\d{4,8}").find(s)?.value            // پشتیبان
    }
}