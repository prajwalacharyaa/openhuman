package app.mochi.phone

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * "Lock the phone when the sleep timer ends": Android lets an app lock the screen only as a device admin with the
 * one policy force-lock (res/xml/lock_admin.xml), which December turns on once under Phone abilities. Nothing else.
 */
class LockAdmin : DeviceAdminReceiver() {
    companion object {
        fun component(c: Context) = ComponentName(c, LockAdmin::class.java)

        fun allowed(c: Context): Boolean =
            c.getSystemService(DevicePolicyManager::class.java).isAdminActive(component(c))

        fun lockIfAllowed(c: Context) {
            if (allowed(c)) runCatching { c.getSystemService(DevicePolicyManager::class.java).lockNow() }
        }

        /** Android's own screen that asks December to allow it. */
        fun ask(c: Context): Intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(c))
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Mochi only locks the screen when a sleep timer you set ends a call. It can't do anything else.")
    }
}
