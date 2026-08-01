package org.thoughtcrime.securesms.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.util.TextSecurePreferences

/**
 * Respond to a PanicKit trigger Intent by locking the app.  PanicKit provides a
 * common framework for creating "panic button" apps that can trigger actions
 * in "panic responder" apps.  In this case, the response is to lock the app,
 * if it has been configured to do so via the Signal lock preference.
 */
class PanicResponderListener : BroadcastReceiver() {

  companion object {
    private val TAG = Log.tag(PanicResponderListener::class.java)
  }

  // AJ fork diagnostic: this receiver is exported (PanicKit requirement) and had no logging
  // at all, so a trigger from any app on the device - matched or not - was invisible in the
  // debug log. Logging every onReceive() here closes that gap without needing adb/dumpsys.
  override fun onReceive(context: Context, intent: Intent) {
    Log.i(TAG, "onReceive() action=" + intent.action + " package=" + intent.`package`)
    val passwordEnabled = TextSecurePreferences.isPassphraseLockEnabled(context)
    val intentAction = intent.action
    if (passwordEnabled && "info.guardianproject.panic.action.TRIGGER" == intentAction) {
      Log.i(TAG, "Valid panic trigger received - locking app.")
      val lockIntent = Intent(context, KeyCachingService::class.java)
      lockIntent.action = KeyCachingService.CLEAR_KEY_ACTION
      context.startService(lockIntent)
    }
  }
}
