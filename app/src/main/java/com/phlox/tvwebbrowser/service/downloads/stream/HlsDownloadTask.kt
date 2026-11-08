package com.phlox.tvwebbrowser.service.downloads.stream

import android.net.Uri
import android.util.Log
import androidx.media3.muxer.MuxerException
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.model.StreamSpec
import com.phlox.tvwebbrowser.net.ProxySettings
import com.phlox.tvwebbrowser.service.downloads.DownloadErrorException
import com.phlox.tvwebbrowser.service.downloads.DownloadTask
import com.phlox.tvwebbrowser.service.downloads.HttpDownloads
import com.phlox.tvwebbrowser.service.downloads.HttpException
import com.phlox.tvwebbrowser.service.downloads.StorageException
import com.phlox.tvwebbrowser.service.downloads.openOutput
import com.phlox.tvwebbrowser.utils.DownloadFiles
import com.phlox.tvwebbrowser.utils.StreamWorkDir
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Saves an HLS stream ([Download.streamSpec]) as one MP4 file. Its segments are downloaded (and decrypted) into
 * the [StreamWorkDir] of the download first, each one into its own file, so a paused or interrupted download
 * goes on with the segments it does not have yet; then [StreamMuxer] packs them into the file, deleting each
 * segment once it is written. The progress is in the bytes of the segments, the size estimated from those
 * already there; while the file is packed, [Download.bytesReceived] equals [Download.size].
 */
class HlsDownloadTask(download: Download, listener: Listener, folder: Uri?, private val proxy: ProxySettings? = null) :
    DownloadTask(download, listener, folder) {
    companion object {
        private val TAG: String = HlsDownloadTask::class.java.simpleName
        private const val MAX_PLAYLIST_SIZE = 8 * 1024 * 1024
        private const val MAX_KEY_SIZE = 1024
        private const val SEGMENT_ATTEMPTS = 3
        private const val RETRY_DELAY = 2000L
        private const val BUFFER_SIZE = 64 * 1024
        /** What the folder holds: the segments of the plan with this description, else they are dropped */
        private const val PLAN_FILE = "plan"
    }

    private val context get() = AppContext.get()
    private val keys = HashMap<String, ByteArray>()

    override fun openInput(): InputStream = throw UnsupportedOperationException()

    override fun transfer() {
        val spec = StreamSpec.fromJson(download.streamSpec) ?: throw DownloadErrorException(Download.Error.FORMAT)
        val plan = loadPlan(spec)
        val dir = StreamWorkDir.of(context, download.id)
        if (!dir.isDirectory && !dir.mkdirs()) throw StorageException(IOException("Can not create $dir"))
        keepSegmentsOf(plan, dir)
        //a file a stopped packing left
        if (download.filepath.isNotEmpty()) {
            DownloadFiles.delete(context, download.filepath)
            download.filepath = ""
        }
        var doneUs = 0L
        download.bytesReceived = 0
        for (segment in plan.segments) {
            val file = segmentFile(dir, segment.index)
            if (file.exists()) {
                download.bytesReceived += file.length()
                doneUs += segment.durationUs
            }
        }
        estimateSize(plan, doneUs, spec)
        runBlocking { repository.update(download) }

        plan.inits.forEachIndexed { i, init -> if (!isStopped()) downloadPiece(init, initFile(dir, i), countBytes = false) }
        for (segment in plan.segments) {
            if (isStopped()) return
            val file = segmentFile(dir, segment.index)
            if (file.exists()) continue
            downloadPiece(segment.piece, file, countBytes = true)
            if (isStopped()) return
            doneUs += segment.durationUs
            estimateSize(plan, doneUs, spec)
            reportProgress()
        }
        if (isStopped()) return
        pack(plan, dir)
    }

    private fun isStopped() = stopRequest != Stop.NONE

    private fun loadPlan(spec: StreamSpec): HlsPlan {
        val text = HttpDownloads.fetch(download, spec.url, proxy = proxy).let { response ->
            try {
                String(readAll(response.input, MAX_PLAYLIST_SIZE), Charsets.UTF_8)
            } finally {
                response.connection?.disconnect()
            }
        }
        return try {
            HlsPlan.parse(text, spec.url)
        } catch (e: HlsPlan.UnsupportedStreamException) {
            throw DownloadErrorException(when (e.problem) {
                HlsPlan.Problem.LIVE -> Download.Error.LIVE
                HlsPlan.Problem.PROTECTED -> Download.Error.PROTECTED
                else -> Download.Error.FORMAT
            }, e.message)
        } catch (e: IOException) {
            //the parser of Media3 (ParserException): not a playlist
            throw DownloadErrorException(Download.Error.FORMAT, e.message)
        }
    }

    /** Drops the segments of another plan (the playlist changed since the download started) */
    private fun keepSegmentsOf(plan: HlsPlan, dir: File) {
        val description = "${plan.segments.size} ${plan.durationUs} ${plan.inits.size}"
        val file = File(dir, PLAN_FILE)
        val known = if (file.exists()) file.readText() else null
        if (known == description) return
        dir.listFiles()?.forEach { it.delete() }
        try {
            file.writeText(description)
        } catch (e: IOException) {
            throw StorageException(e)
        }
    }

    private fun segmentFile(dir: File, index: Int) = File(dir, "s$index")

    private fun initFile(dir: File, index: Int) = File(dir, "i$index")

    /** The size of the file from the bytes of the segments there; the size of the bitrate before */
    private fun estimateSize(plan: HlsPlan, doneUs: Long, spec: StreamSpec) {
        download.size = when {
            doneUs > 0 && plan.durationUs > 0 -> (download.bytesReceived.toDouble() / doneUs * plan.durationUs).toLong()
            else -> spec.estimatedSize
        }
        keepSizeAhead()
    }

    /**
     * The estimate stays above the bytes there while segments download: the rows tell the packing of the file by
     * [Download.bytesReceived] reaching [Download.size]
     */
    private fun keepSizeAhead() {
        if (download.size in 1..download.bytesReceived) download.size = download.bytesReceived + 1
    }

    /**
     * Downloads [piece] (decrypted) into [file], through a temporary file, so a file that is there is complete;
     * tries again after errors of the connection. Nothing is written when the download is asked to stop.
     */
    private fun downloadPiece(piece: HlsPlan.Piece, file: File, countBytes: Boolean) {
        if (file.exists()) return
        val part = File(file.parentFile, file.name + ".part")
        var attempt = 0
        while (true) {
            val received = download.bytesReceived
            try {
                val complete = writePiece(piece, part, countBytes)
                if (!complete) {
                    part.delete()
                    if (countBytes) download.bytesReceived = received
                    return
                }
                if (!part.renameTo(file)) throw StorageException(IOException("Can not rename $part"))
                return
            } catch (e: IOException) {
                part.delete()
                if (countBytes) download.bytesReceived = received
                //a refused request is refused again
                val retry = e !is StorageException && e !is DownloadErrorException &&
                    (e !is HttpException || e.code >= 500) && ++attempt < SEGMENT_ATTEMPTS
                if (!retry || isStopped()) throw e
                Log.w(TAG, "Segment ${piece.url} failed, trying again", e)
                Thread.sleep(RETRY_DELAY)
            }
        }
    }

    /** @return false if the download was asked to stop */
    private fun writePiece(piece: HlsPlan.Piece, part: File, countBytes: Boolean): Boolean {
        val response = HttpDownloads.fetch(download, piece.url, piece.offset, piece.length, proxy = proxy)
        try {
            val input = piece.key?.let { decrypting(response.input, it) } ?: response.input
            val output = try {
                FileOutputStream(part)
            } catch (e: IOException) {
                throw StorageException(e)
            }
            output.use {
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    if (isStopped()) return false
                    val count = input.read(buffer)
                    if (count == -1) break
                    try {
                        output.write(buffer, 0, count)
                    } catch (e: IOException) {
                        throw StorageException(e)
                    }
                    if (countBytes) {
                        download.bytesReceived += count
                        keepSizeAhead()
                        reportProgress()
                    }
                }
            }
            return true
        } finally {
            response.connection?.disconnect()
        }
    }

    private fun decrypting(input: InputStream, key: HlsPlan.Key): InputStream {
        val keyBytes = keys.getOrPut(key.url) {
            val response = HttpDownloads.fetch(download, key.url, proxy = proxy)
            try {
                readAll(response.input, MAX_KEY_SIZE)
            } finally {
                response.connection?.disconnect()
            }
        }
        if (keyBytes.size != 16) throw DownloadErrorException(Download.Error.PROTECTED, "A key of ${keyBytes.size} bytes")
        return CipherInputStream(input, HlsCrypto.cipher(keyBytes, key.iv))
    }

    /** Packs the segments into the file of the download */
    private fun pack(plan: HlsPlan, dir: File) {
        download.size = download.bytesReceived
        reportProgress(force = true)
        val resumable = download.resumable
        val output = try {
            openOutput(download, folder)
        } catch (e: Exception) {
            throw StorageException(e)
        }
        this.output = output
        //the file is written anew when the packing stops: its segments are kept until they are written
        download.resumable = resumable
        runBlocking { repository.update(download) }
        val muxer = StreamMuxer(StreamMuxer.muxerFor(output)) { isStopped() }
        try {
            for (chunk in plan.chunks) {
                val inputs = ArrayList<() -> InputStream>()
                chunk.init?.let { init -> inputs += { FileInputStream(initFile(dir, init)) } }
                for (segment in chunk.segments) {
                    val file = segmentFile(dir, segment.index)
                    inputs += { DeletingInputStream(file) }
                }
                sequenceOf(inputs).use { muxer.writeChunk(it, fragmentedMp4 = chunk.init != null, startUs = chunk.startUs) }
            }
            muxer.finish()
        } catch (e: StreamMuxer.StoppedException) {
            dropOutput()
            return
        } catch (e: StreamMuxer.FormatException) {
            dropOutput()
            throw DownloadErrorException(Download.Error.FORMAT, e.message)
        } catch (e: MuxerException) {
            dropOutput()
            throw StorageException(e)
        } catch (e: IOException) {
            dropOutput()
            throw e
        } catch (e: RuntimeException) {
            //the extractors and the muxer check what they get with exceptions of their own
            dropOutput()
            throw DownloadErrorException(Download.Error.FORMAT, e.message)
        }
        this.output = null
        val size = DownloadFiles.sizeOf(context, download.filepath)
        if (size > 0) download.bytesReceived = size
        StreamWorkDir.delete(context, download.id)
    }

    /** Deletes the file being packed: a resumed download packs it again */
    private fun dropOutput() {
        try {
            output?.close()
        } catch (ignored: IOException) {
        }
        output = null
        DownloadFiles.delete(context, download.filepath)
        download.filepath = ""
    }

    private fun readAll(input: InputStream, max: Int): ByteArray {
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            result.write(buffer, 0, count)
            if (result.size() > max) throw DownloadErrorException(Download.Error.FORMAT, "Too big")
        }
        return result.toByteArray()
    }

    /** A segment file deleted once it is read to its end */
    private class DeletingInputStream(private val file: File) : FilterInputStream(FileInputStream(file)) {
        private var ended = false

        override fun read(): Int = super.read().also { if (it == -1) ended = true }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it == -1) ended = true }

        override fun close() {
            super.close()
            if (ended) file.delete()
        }
    }
}

object HlsCrypto {
    /** The cipher of segments encrypted with AES-128 (CBC, PKCS7 padding) */
    fun cipher(key: ByteArray, iv: ByteArray): Cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    }
}
