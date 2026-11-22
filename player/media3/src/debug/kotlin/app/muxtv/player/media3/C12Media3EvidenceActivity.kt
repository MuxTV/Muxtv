package app.muxtv.player.media3

import android.app.Activity
import android.os.Bundle
import android.view.SurfaceView
import android.view.WindowManager

/** Debug-only surface owner used exclusively by C12 no-first-frame instrumentation evidence. */
class C12Media3EvidenceActivity : Activity() {
    lateinit var surfaceView: SurfaceView
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        surfaceView = SurfaceView(this)
        setContentView(surfaceView)
    }
}
