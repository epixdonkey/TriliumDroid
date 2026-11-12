package eu.fliegendewurst.triliumdroid.util

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import eu.fliegendewurst.triliumdroid.R

/**
 * App lock using the system biometric prompt (fingerprint, face) with fallback to the
 * device credential (PIN / pattern / password).
 *
 * Lock state is process-wide: the app is locked on process start and whenever the main
 * activity is stopped (moved to background), unless the stop was caused by the app itself
 * (see [allowInternalNavigation]).
 *
 * A *quick note session* (started from the quick note widget) allows editing the freshly
 * created note without authentication. Everything else stays locked until the user authenticates.
 */
object BiometricAuthManager {
	private var unlocked: Boolean = false
	private var quickNoteSession: Boolean = false
	private var promptShowing: Boolean = false
	private var skipNextStopLock: Boolean = false

	/**
	 * Whether the lock can be used on this device: requires Android 10+ (device credential
	 * fallback in the system prompt) and a configured screen lock.
	 */
	fun canAuthenticate(context: Context): Boolean {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
			return false
		}
		val keyguard = context.getSystemService(KeyguardManager::class.java)
		return keyguard?.isDeviceSecure == true
	}

	fun isLocked(): Boolean = Preferences.biometricLock() && !unlocked

	/**
	 * True while the user may only edit the quick note, without access to other notes.
	 */
	fun isQuickNoteRestricted(): Boolean = isLocked() && quickNoteSession

	fun markUnlocked() {
		unlocked = true
		quickNoteSession = false
	}

	fun enterQuickNoteRestrictedSession() {
		if (isLocked()) {
			quickNoteSession = true
		}
	}

	/**
	 * Call before starting another activity of this app (settings, about, share chooser),
	 * so that the resulting stop of the main activity does not lock the app.
	 */
	fun allowInternalNavigation() {
		skipNextStopLock = true
	}

	fun onMainActivityStopped(isChangingConfigurations: Boolean) {
		// the device credential screen of the prompt stops the activity as well
		if (isChangingConfigurations || promptShowing) {
			return
		}
		if (skipNextStopLock) {
			skipNextStopLock = false
			return
		}
		unlocked = false
		quickNoteSession = false
	}

	/**
	 * Ask the user to authenticate. [onSuccess] is called immediately if the app is not locked.
	 * If the lock can no longer be used (e.g. screen lock removed in system settings),
	 * the app is unlocked, since no credential exists to protect it with.
	 */
	fun authenticate(
		activity: AppCompatActivity,
		onSuccess: () -> Unit,
		onCancelOrError: () -> Unit = {}
	) {
		if (!isLocked() || !canAuthenticate(activity)) {
			markUnlocked()
			onSuccess()
			return
		}
		if (promptShowing || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
			return
		}
		promptShowing = true
		val executor = ContextCompat.getMainExecutor(activity)
		val builder = BiometricPrompt.Builder(activity)
			.setTitle(activity.getString(R.string.biometric_prompt_title))
			.setSubtitle(activity.getString(R.string.biometric_prompt_subtitle))
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			builder.setAllowedAuthenticators(
				BiometricManager.Authenticators.BIOMETRIC_WEAK or
						BiometricManager.Authenticators.DEVICE_CREDENTIAL
			)
		} else {
			@Suppress("DEPRECATION")
			builder.setDeviceCredentialAllowed(true)
		}
		builder.build().authenticate(
			CancellationSignal(),
			executor,
			object : BiometricPrompt.AuthenticationCallback() {
				override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
					promptShowing = false
					markUnlocked()
					onSuccess()
				}

				override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
					promptShowing = false
					onCancelOrError()
				}
			}
		)
	}
}
