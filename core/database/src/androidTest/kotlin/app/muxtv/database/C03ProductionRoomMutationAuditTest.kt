package app.muxtv.database

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muxtv.database.measurement.C03ProductionRoomMutationAudit
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class C03ProductionRoomMutationAuditTest {
    @Test
    fun countsActualInsertUpdateDeleteMutationsInsteadOfNetRowDelta() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "c03-production-mutation-audit-test.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        val database = SQLiteDatabase.openOrCreateDatabase(file, null)

        try {
            database.enableWriteAheadLogging()
            database.execSQL("CREATE TABLE measured_rows(id INTEGER PRIMARY KEY, value TEXT NOT NULL)")
            C03ProductionRoomMutationAudit.install(database, setOf("measured_rows"))

            database.execSQL("INSERT INTO measured_rows(id, value) VALUES(1, 'one')")
            database.execSQL("UPDATE measured_rows SET value='two' WHERE id=1")
            database.execSQL("DELETE FROM measured_rows WHERE id=1")

            val snapshot = C03ProductionRoomMutationAudit.snapshot(database)
            val measured = snapshot.table("measured_rows")
            assertThat(measured.inserts).isEqualTo(1L)
            assertThat(measured.updates).isEqualTo(1L)
            assertThat(measured.deletes).isEqualTo(1L)
            assertThat(measured.total).isEqualTo(3L)
        } finally {
            database.close()
            context.deleteDatabase(name)
            File(file.path + "-wal").delete()
            File(file.path + "-shm").delete()
        }
    }
}
