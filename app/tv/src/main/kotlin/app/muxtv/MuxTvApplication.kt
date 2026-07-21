package app.muxtv

import android.app.Application
import app.muxtv.database.DatabaseInitializer
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@HiltAndroidApp
class MuxTvApplication : Application() {
    @Inject lateinit var databaseInitializer: DatabaseInitializer
    @Inject @ApplicationIoScope lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch { databaseInitializer.initialize() }
    }
}
