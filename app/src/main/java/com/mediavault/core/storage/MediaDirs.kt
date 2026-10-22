package com.mediavault.core.storage

import android.content.Context
import com.mediavault.core.model.MediaKind
import java.io.File

object MediaDirs {
    /** App-specific storage: needs no storage permission and is removed on uninstall. */
    fun dirFor(context: Context, kind: MediaKind): File {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        return File(root, kind.folder).also { it.mkdirs() }
    }
}
