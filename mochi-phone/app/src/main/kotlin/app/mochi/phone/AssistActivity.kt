package app.mochi.phone

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** The phone's assistant gesture (when Mochi is the digital assistant): start a Mochi call. */
class AssistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true) // the gesture works on the lock screen too (the call screen shows there already)
        if (intent?.action != Intent.ACTION_ASSIST) return finish() // only the system's assistant gesture
        if (Mochi.configured(this)) {
            startActivity(Intent(this, CallActivity::class.java).putExtra(CallActivity.EXTRA_ANSWER, true))
        } else {
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }
}
