/*
 * NovaMusic — GPL-3.0.
 *
 * Importing a parametric EQ profile file. Echo's repository read whatever path it was given
 * with File.readText(), which is unbounded; NovaMusic only ever reads through the Storage
 * Access Framework and refuses anything above ParametricEqParser.MAX_PROFILE_BYTES.
 */

package com.novamusic.app.eq

import android.content.Context
import android.net.Uri
import com.novamusic.app.eq.data.ParametricEqParser
import java.io.InputStream
import timber.log.Timber

/**
 * Reads a profile the user picked with `ActivityResultContracts.OpenDocument`.
 *
 * @return the file's text, or a failure whose message is safe to show in a snackbar.
 */
fun readProfileText(context: Context, uri: Uri): Result<String> {
    // Providers may report UNKNOWN_LENGTH (-1); only trust a positive length.
    val declaredSize =
        runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()

    if (declaredSize != null && declaredSize > ParametricEqParser.MAX_PROFILE_BYTES) {
        return Result.failure(
            IllegalArgumentException(
                "That file is ${declaredSize / 1024} KiB. " +
                    "The maximum is ${ParametricEqParser.MAX_PROFILE_BYTES / 1024} KiB.",
            ),
        )
    }

    // null covers every failure mode: no stream, a read error, or a stream that exceeded
    // the cap (which a provider lying about its length can still cause).
    val text: String? =
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.readCapped(ParametricEqParser.MAX_PROFILE_BYTES)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not read the selected EQ profile")
            null
        }

    if (text == null) {
        return Result.failure(
            IllegalArgumentException(
                "That file could not be read. It may be missing or unreadable, or larger than " +
                    "${ParametricEqParser.MAX_PROFILE_BYTES / 1024} KiB.",
            ),
        )
    }

    return Result.success(text)
}

/**
 * Reads at most [maxBytes] and returns null if the stream has more.
 *
 * Reading byte-by-byte rather than via `readBytes()` is deliberate: it bounds memory even
 * when the content provider reports a length we cannot trust.
 */
private fun InputStream.readCapped(maxBytes: Int): String? {
    val buffer = ByteArray(8 * 1024)
    val out = StringBuilder()
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read == -1) break
        total += read
        if (total > maxBytes) return null
        out.append(String(buffer, 0, read, Charsets.UTF_8))
    }
    return out.toString()
}

private const val TAG = "ParametricEqImport"
