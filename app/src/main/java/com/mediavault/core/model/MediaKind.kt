package com.mediavault.core.model

enum class MediaKind(val folder: String, val extension: String) {
    PHOTO("Pictures", "jpg"),
    VIDEO("Movies", "mp4"),
    AUDIO("Audio", "m4a"),
    DOCUMENT("Documents", "pdf"),
}
