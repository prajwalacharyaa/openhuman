package app.mochi.phone

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telephony.SmsManager
import android.widget.LinearLayout

/**
 * "Call Mom?" / "Text Sita: I'm late?" — the phone-side gate for Mochi's outward actions. Nothing is called
 * or sent unless December taps Confirm here; the answer goes back to Mochi through [PhoneActions].
 */
class ConfirmActivity : Activity() {
    private var answered = false
    private val id get() = intent.getStringExtra(EXTRA_ID) ?: ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        getSystemService(NotificationManager::class.java).cancel(intent.getIntExtra(EXTRA_NOTIFICATION, 0))
        val kind = intent.getStringExtra(EXTRA_KIND) ?: "call"
        val name = intent.getStringExtra(EXTRA_NAME) ?: ""
        val number = intent.getStringExtra(EXTRA_NUMBER) ?: ""
        val text = intent.getStringExtra(EXTRA_TEXT) ?: ""

        val screen = Ui.screen(this, if (kind == "call") "Call $name?" else "Text $name?", back = false)
        val card = Ui.card(this)
        card.addView(Ui.text(this, number, 15f, Ui.MUTED))
        if (kind == "sms") card.addView(Ui.text(this, text, 17f).apply { setPadding(0, Ui.dp(this@ConfirmActivity, 8), 0, 0) })
        card.addView(Ui.text(this, "Mochi asked for this. Nothing happens unless you confirm.", 13f, Ui.MUTED)
            .apply { setPadding(0, Ui.dp(this@ConfirmActivity, 12), 0, 0) })
        screen.root.addView(card)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Ui.button(this, "Cancel", Ui.GREY) { act { PhoneActions.failed("December cancelled it") } },
            LinearLayout.LayoutParams(0, Ui.dp(this, 56), 1f).apply { marginEnd = Ui.dp(this@ConfirmActivity, 6) })
        row.addView(Ui.button(this, if (kind == "call") "Call" else "Send", Ui.GREEN) {
            // on a locked phone, unlocking comes first: whoever holds it can't confirm
            unlocked { act { if (kind == "call") call(number, name) else sms(number, name, text) } }
        }, LinearLayout.LayoutParams(0, Ui.dp(this, 56), 1f).apply { marginStart = Ui.dp(this@ConfirmActivity, 6) })
        screen.root.addView(row, Ui.matchWidth(this, 16))
    }

    private fun call(number: String, name: String) = try {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            if (Call.active) Call.end() // the Mochi call gives way to the real one
            val call = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
            PhoneActions.simHandle(this, intent.getIntExtra(EXTRA_SIM, 0))
                ?.let { call.putExtra(android.telecom.TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
            startActivity(call)
            PhoneActions.answered("calling $name now")
        } else {
            // without the Phone permission: the dialer opens with the number, one more tap to call
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
            PhoneActions.answered("opened the dialer with $name's number (tap the call button)")
        }
    } catch (e: Exception) {
        PhoneActions.failed("couldn't start the call: ${PhoneActions.hideNumbers(e.message ?: "")}")
    }

    private fun sms(number: String, name: String, text: String) = try {
        if (checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
            val sms = getSystemService(SmsManager::class.java)
            sms.sendMultipartTextMessage(number, null, sms.divideMessage(text), null, null)
            PhoneActions.answered("handed to the phone to send to $name")
        } else {
            startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).putExtra("sms_body", text))
            PhoneActions.answered("opened Messages with the text for $name (tap send)")
        }
    } catch (e: Exception) {
        PhoneActions.failed("couldn't send it: ${PhoneActions.hideNumbers(e.message ?: "")}")
    }

    /** Once only (a double tap does nothing), and only while Mochi is still waiting: after a timeout, nothing. */
    private fun act(block: () -> org.json.JSONObject) {
        if (answered) return
        answered = true
        if (PhoneActions.isPending(id)) PhoneActions.answer(id, block())
        finish()
    }

    private fun unlocked(then: () -> Unit) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) return then()
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = then()
        })
    }

    override fun onDestroy() {
        if (!answered && isFinishing) PhoneActions.answer(id, PhoneActions.failed("December closed it without confirming"))
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_KIND = "kind"
        const val EXTRA_NAME = "name"
        const val EXTRA_NUMBER = "number"
        const val EXTRA_TEXT = "text"
        const val EXTRA_NOTIFICATION = "notification"
        const val EXTRA_SIM = "sim"
    }
}
