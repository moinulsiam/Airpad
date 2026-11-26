package com.dris.airpad

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * File transfer over TCP (port 50506).
 *  phone -> PC :  U|pin|size|name \n + bytes   -> PC answers OK
 *  PC -> phone :  G|pin \n  -> PC answers  F|size|name \n + bytes  (or N), phone answers A
 * say(text, isProgress): isProgress=true updates the one-line status, false adds a log line.
 */
class FileTransfer(
    private val ctx: Context,
    private val link: WifiLink,
    private val say: (String, Boolean) -> Unit
) {
    private val port = 50506
    private val pulling = AtomicBoolean(false)
    @Volatile private var blockUntil = 0L

    // ------------------------------------------------------------ phone -> PC
    fun sendUris(uris: List<Uri>) {
        Thread {
            for (u in uris) {
                try {
                    upload(u)
                } catch (e: Exception) {
                    say("Send failed: " + e.message, false)
                }
            }
            say("", true)
        }.start()
    }

    private fun upload(uri: Uri) {
        val host = link.host
        val pin = link.pin
        if (host.isEmpty() || pin.isEmpty()) throw IOException("PC not connected yet")

        var name = "file"
        var size = -1L
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) name = c.getString(ni) ?: name
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        if (size < 0) {
            ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { size = it.length }
        }
        if (size < 0) throw IOException("Unknown file size")
        name = name.replace("\n", " ").replace("\r", " ")

        val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open file")
        input.use { ins ->
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), 5000)
                s.soTimeout = 30000
                val out = s.getOutputStream()
                out.write(("U|$pin|$size|$name\n").toByteArray())
                val buf = ByteArray(65536)
                var sent = 0L
                var lastPct = -1
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    sent += n
                    val pct = if (size > 0) (sent * 100 / size).toInt() else 100
                    if (pct / 5 != lastPct / 5) {
                        lastPct = pct
                        say("Sending $name  $pct%", true)
                    }
                }
                out.flush()
                val resp = readLine(s.getInputStream())
                if (resp != "OK") throw IOException("PC refused (check PIN)")
                say("Sent to PC: $name", false)
            }
        }
    }

    // ------------------------------------------------------------ PC -> phone
    /** Downloads everything that is waiting in the PC's "send to phone" queue. */
    fun pull() {
        if (System.currentTimeMillis() < blockUntil) return
        if (!pulling.compareAndSet(false, true)) return
        Thread {
            try {
                val host = link.host
                val pin = link.pin
                if (host.isNotEmpty() && pin.isNotEmpty()) {
                    while (pullOne(host, pin)) {
                        // keep going until the queue is empty
                    }
                }
            } catch (e: Exception) {
                say("Receive failed: " + e.message, false)
                blockUntil = System.currentTimeMillis() + 15000
            } finally {
                pulling.set(false)
                say("", true)
            }
        }.start()
    }

    private fun pullOne(host: String, pin: String): Boolean {
        return Socket().use { s ->
            s.connect(InetSocketAddress(host, port), 5000)
            s.soTimeout = 30000
            val ins = BufferedInputStream(s.getInputStream())
            val out = s.getOutputStream()
            out.write(("G|$pin\n").toByteArray())
            out.flush()
            val line = readLine(ins)
            if (!line.startsWith("F|")) return@use false
            val parts = line.split("|", limit = 3)
            val size = parts[1].toLong()
            val name = parts[2]

            val (os, cleanup) = openOut(name)
            var ok = false
            try {
                os.use { o ->
                    var remaining = size
                    var done = 0L
                    var lastPct = -1
                    val buf = ByteArray(65536)
                    while (remaining > 0) {
                        val want = minOf(buf.size.toLong(), remaining).toInt()
                        val n = ins.read(buf, 0, want)
                        if (n < 0) throw IOException("Connection closed")
                        o.write(buf, 0, n)
                        remaining -= n
                        done += n
                        val pct = if (size > 0) (done * 100 / size).toInt() else 100
                        if (pct / 5 != lastPct / 5) {
                            lastPct = pct
                            say("Receiving $name  $pct%", true)
                        }
                    }
                }
                ok = true
            } finally {
                if (!ok) cleanup()
            }
            out.write("A\n".toByteArray())
            out.flush()
            say("Received from PC: $name  (Downloads/AirPad)", false)
            true
        }
    }

    private fun openOut(name: String): Pair<OutputStream, () -> Unit> {
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "file" }
        if (Build.VERSION.SDK_INT >= 29) {
            val ext = safe.substringAfterLast('.', "").lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            val v = ContentValues()
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, safe)
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AirPad")
            if (mime != null) v.put(MediaStore.MediaColumns.MIME_TYPE, mime)
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                ?: throw IOException("Cannot create file")
            val os = ctx.contentResolver.openOutputStream(uri) ?: throw IOException("Cannot open file")
            val cleanup: () -> Unit = { ctx.contentResolver.delete(uri, null, null) }
            return Pair(os, cleanup)
        } else {
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            val f = File(dir, safe)
            val cleanup: () -> Unit = { f.delete() }
            return Pair(f.outputStream(), cleanup)
        }
    }

    private fun readLine(ins: InputStream): String {
        val sb = ByteArrayOutputStream()
        while (sb.size() < 4096) {
            val b = ins.read()
            if (b < 0 || b == '\n'.code) break
            sb.write(b)
        }
        return sb.toString("UTF-8").trim()
    }
}
