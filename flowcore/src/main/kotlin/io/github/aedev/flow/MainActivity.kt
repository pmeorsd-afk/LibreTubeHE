package io.github.aedev.flow

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Rational

/**
 * Flow services and notifications reference the original Flow MainActivity.
 * In LibreTubeHE this lightweight bridge simply returns users to the host app.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(launchIntent)
        }
        finish()
    }

    fun enterPlayerPictureInPictureMode(
        aspectRatioWidth: Int = 16,
        aspectRatioHeight: Int = 9,
        isPlaying: Boolean = true
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return runCatching {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(aspectRatioWidth, aspectRatioHeight))
                .build()
            enterPictureInPictureMode(params)
        }.getOrDefault(false)
    }
}
