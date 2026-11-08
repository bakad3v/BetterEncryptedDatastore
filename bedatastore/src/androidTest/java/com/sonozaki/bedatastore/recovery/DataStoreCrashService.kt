package com.sonozaki.bedatastore.recovery

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Messenger
import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okio.Buffer
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.ForwardingSource
import okio.Path
import okio.Source
import java.io.File

class DataStoreCrashService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onBind(intent: Intent): IBinder {
        val files = RecoveryFiles(File(requireNotNull(intent.getStringExtra(EXTRA_DIRECTORY))))
        val point = CrashPoint.valueOf(requireNotNull(intent.getStringExtra(EXTRA_CRASH_POINT)))
        // The test sends a message only after it is listening for this process to die.
        return Messenger(Handler(Looper.getMainLooper()) {
            scope.launch { runUntilCrash(files, point) }
            true
        }).binder
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runUntilCrash(files: RecoveryFiles, point: CrashPoint) {
        val migrations = if (point == CrashPoint.BEFORE_MIGRATION_REPLACE) {
            listOf(files.migration)
        } else {
            emptyList()
        }
        val store = files.open(scope, CrashFileSystem(files, point), migrations)
        when (point) {
            CrashPoint.DURING_READ, CrashPoint.BEFORE_MIGRATION_REPLACE -> store.data.first()
            CrashPoint.BEFORE_REPLACE, CrashPoint.AFTER_REPLACE -> store.updateData { NEW_VALUE }
        }
        error("The operation completed before reaching $point")
    }

    private class CrashFileSystem(
        private val files: RecoveryFiles,
        private val point: CrashPoint,
    ) : ForwardingFileSystem(FileSystem.SYSTEM) {
        override fun source(file: Path): Source {
            val source = super.source(file)
            if (point != CrashPoint.DURING_READ) return source
            return object : ForwardingSource(source) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    // Read just the first byte so the serializer never sees a complete value.
                    check(super.read(sink, 1) == 1L) { "The store file is empty" }
                    crash()
                }
            }
        }

        override fun atomicMove(source: Path, target: Path) {
            when (point) {
                CrashPoint.BEFORE_REPLACE, CrashPoint.BEFORE_MIGRATION_REPLACE -> crash()
                CrashPoint.AFTER_REPLACE -> {
                    super.atomicMove(source, target)
                    crash()
                }
                CrashPoint.DURING_READ -> error("A plain read must not replace the store file")
            }
        }

        private fun crash(): Nothing {
            // Persist only the checkpoint name so an unrelated process crash fails the test.
            files.checkpoint.writeText(point.name)
            Process.killProcess(Process.myPid())
            error("The worker survived its kill signal")
        }
    }
}
