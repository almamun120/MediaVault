package com.mediavault.core.util

import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Renders file names from templates such as `IMG_{yyyyMMdd_HHmmss}`.
 * Text inside braces is a java.time pattern; everything else is literal.
 */
object FilenameTemplate {
    private val token = Regex("\\{([^{}]+)}")
    private val illegal = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

    fun render(template: String, time: ZonedDateTime): String =
        sanitize(
            token.replace(template) { m ->
                try {
                    DateTimeFormatter.ofPattern(m.groupValues[1]).format(time)
                } catch (e: IllegalArgumentException) {
                    m.value
                }
            },
        )

    /** Renders a folder template like `Photos/{yyyy}/{MM}`: each segment is rendered and sanitised separately. */
    fun renderPath(template: String, time: ZonedDateTime): String =
        template.split('/', '\\').filter { it.isNotBlank() && it != "." && it != ".." }
            .joinToString("/") { render(it, time) }

    fun sanitize(name: String): String =
        name.replace(illegal, "_").trim().trim('.').take(200).ifEmpty { "file" }

    /** Never overwrites: appends _1, _2, ... until the name is free. */
    fun uniqueFile(dir: File, baseName: String, extension: String): File {
        var candidate = File(dir, "$baseName.$extension")
        var i = 1
        while (candidate.exists()) {
            candidate = File(dir, "${baseName}_$i.$extension")
            i++
        }
        return candidate
    }
}
