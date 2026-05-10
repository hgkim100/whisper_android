package com.hgkim.whisperandroid

import android.app.Application

/**
 * Process-wide [Application] holding the single [AppContainer] instance the
 * activity-side wiring resolves through.
 *
 * Registered in `AndroidManifest.xml` via `android:name=".WhisperApp"`.
 */
class WhisperApp : Application() {

    /** Lazy so unit tests can construct subclasses without paying for the asset I/O. */
    val container: AppContainer by lazy { AppContainer(applicationContext) }
}
