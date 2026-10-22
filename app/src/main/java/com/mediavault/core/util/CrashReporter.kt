package com.mediavault.core.util

import android.app.Application
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import java.io.File

/**
 * Saves the stack trace of an uncrashed-handled exception to app storage (nothing is sent anywhere) and shows it
 * on the next launch so it can be copied and reported without adb.
 */
object CrashReporter {
    private const val FILE = "last_crash.txt"

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { File(app.filesDir, FILE).writeText("Thread: ${thread.name}\n" + Log.getStackTraceString(e)) }
            previous?.uncaughtException(thread, e)
        }
    }

    fun file(app: android.content.Context) = File(app.filesDir, FILE)
}

@Composable
fun CrashDialog() {
    val ctx = LocalContext.current
    val file = remember { CrashReporter.file(ctx) }
    var text by remember { mutableStateOf(if (file.exists()) runCatching { file.readText() }.getOrNull() else null) }
    val clipboard = LocalClipboardManager.current
    text?.let { t ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("The app crashed last time") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(t.take(6000), style = MaterialTheme.typography.bodySmall) } },
            confirmButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(t)) }) { Text("Copy") } },
            dismissButton = { TextButton(onClick = { file.delete(); text = null }) { Text("Dismiss") } },
        )
    }
}
