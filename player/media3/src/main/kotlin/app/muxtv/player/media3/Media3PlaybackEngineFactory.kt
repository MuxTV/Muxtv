package app.muxtv.player.media3

import android.content.Context
import app.muxtv.player.PlaybackEngine

object Media3PlaybackEngineFactory {
    fun create(context: Context): PlaybackEngine = Media3PlaybackEngine(context.applicationContext)
}
