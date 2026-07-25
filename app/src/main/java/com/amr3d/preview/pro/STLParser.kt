package com.amr3d.preview.pro

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.*
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

data class STLModel(
    val vertices: FloatArray,
    val normals: FloatArray,
    val triangleCount: Int,
    val minBounds: FloatArray,
    val maxBounds: FloatArray,
    val isWatertightHint: Boolean
)

class STLParseException(message: String) : Exception(message)

object STLParserMT {

    private const val THREAD_COUNT = 4
    private const val PROGRESSIVE_FIRST_BATCH = 50_000
    private const val MAX_FILE_SIZE = 2_000_000_000L
    private const val BINARY_TRIANGLE_SIZE = 50

    @Volatile private var globalMinX = Float.MAX_VALUE
    @Volatile private var globalMinY = Float.MAX_VALUE
    @Volatile private var globalMinZ = Float.MAX_VALUE
    @Volatile private var globalMaxX = -Float.MAX_VALUE
    @Volatile private var globalMaxY = -Float.MAX_VALUE
    @Volatile private var globalMaxZ = -Float.MAX_VALUE

    private fun safeTriangleCap(): Int {
        val maxHeapBytes = Runtime.getRuntime().maxMemory()
        val budgetBytes = (maxHeapBytes * 0.18).toLong()
        val bytesPerTriangle = 72L
        val cap = (budgetBytes / bytesPerTriangle)
        return cap.coerceIn(250_000L, 4_000_000L).toInt()
    }

    private fun updateGlobalBounds(x: Float, y: Float, z: Float) {
        synchronized(this) {
            if (x < globalMinX) globalMinX = x
            if (y < globalMinY) globalMinY = y
            if (z < globalMinZ) globalMinZ = z
            if (x > globalMaxX) globalMaxX = x
            if (y > globalMaxY) globalMaxY = y
            if (z > globalMaxZ) globalMaxZ = z
        }
    }

    private fun getFileSize(context: Context, uri: Uri): Long {
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else -1L
        }?: -1L
    }

    private fun readHeader(context: Context, uri: Uri): ByteArray {
        val header = ByteArray(84)
        context.contentResolver.openInputStream(uri)?.use { it.read(header) }
           ?: throw STLParseException("Cannot open file")
        return header
    }

    suspend fun parseMT(
        context: Context,
        uri: Uri,
        onProgress: (Int) -> Unit = {},
        onFirstBatchReady: (STLModel) -> Unit = {}
    ): STLModel = withContext(Dispatchers.IO) {

        // Reset bounds
        globalMinX = Float.MAX_VALUE; globalMinY = Float.MAX_VALUE; globalMinZ = Float.MAX_VALUE
        globalMaxX = -Float.MAX_VALUE; globalMaxY = -Float.MAX_VALUE; globalMaxZ = -Float.MAX_VALUE

        val fileSize = getFileSize(context, uri)
        if (fileSize <= 0 || fileSize > MAX_FILE_SIZE) throw STLParseException("Invalid file size")

        val header = readHeader(context, uri)
        val triangleCount = ByteBuffer.wrap(header, 80, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (triangleCount <= 0) throw STLParseException("No triangles")

        val maxTriangles = safeTriangleCap()
        val stride = if (triangleCount > maxTriangles) Math.ceil(triangleCount.toDouble() / maxTriangles).toInt() else 1
        val keptCapacity = (triangleCount + stride - 1) / stride

        val vertices = FloatArray(keptCapacity * 9)
        val normals = FloatArray(keptCapacity * 9)
        val vIdx = AtomicInteger(0)
        val keptTriangles = AtomicInteger(0)
        val progressCounter = AtomicInteger(0)
        val firstBatchSent = AtomicInteger(0)

        val trianglesPerThread = triangleCount / THREAD_COUNT

        val jobs = (0 until THREAD_COUNT).map { threadId ->
            async {
                val startTri = threadId * trianglesPerThread
                val endTri = if (threadId == THREAD_COUNT - 1) triangleCount else startTri + trianglesPerThread
                parseChunk(context, uri, startTri, endTri, stride, triangleCount, vertices, normals, vIdx, keptTriangles, progressCounter, firstBatchSent, onProgress, onFirstBatchReady)
            }
        }
        jobs.awaitAll()

        val finalKept = keptTriangles.get()
        STLModel(
            vertices = vertices.copyOf(vIdx.get()),
            normals = normals.copyOf(vIdx.get()),
            triangleCount = finalKept,
            minBounds = floatArrayOf(globalMinX, globalMinY, globalMinZ),
            maxBounds = floatArrayOf(globalMaxX, globalMaxY, globalMaxZ),
            isWatertightHint = (finalKept % 2 == 0)
        )
    }

    private fun parseChunk(
        context: Context, uri: Uri, startTri: Int, endTri: Int, stride: Int, totalTriangles: Int,
        vertices: FloatArray, normals: FloatArray, vIdx: AtomicInteger, keptTriangles: AtomicInteger,
        progressCounter: AtomicInteger, firstBatchSent: AtomicInteger,
        onProgress: (Int) -> Unit, onFirstBatchReady: (STLModel) -> Unit
    ) {
        val inputStream = context.contentResolver.openInputStream(uri)?: return
        inputStream.skip(84 + startTri.toLong() * BINARY_TRIANGLE_SIZE)

        val triangleBytes = ByteArray(BINARY_TRIANGLE_SIZE)
        val progressStep = maxOf(totalTriangles / 100, 500)

        for (t in startTri until endTri) {
            if (inputStream.read(triangleBytes)!= BINARY_TRIANGLE_SIZE) break

            val buffer = ByteBuffer.wrap(triangleBytes).order(ByteOrder.LITTLE_ENDIAN)
            val nx = buffer.float; val ny = buffer.float; val nz = buffer.float
            val keepThis = (t % stride == 0) && keptTriangles.get() < (vertices.size / 9)

            for (v in 0 until 3) {
                val x = buffer.float; val y = buffer.float; val z = buffer.float
                updateGlobalBounds(x, y, z)

                if (keepThis) {
                    val idx = vIdx.getAndAdd(3)
                    vertices[idx] = x; vertices[idx + 1] = y; vertices[idx + 2] = z
                    normals[idx] = nx; normals[idx + 1] = ny; normals[idx + 2] = nz
                }
            }
            buffer.short // skip 2 bytes

            if (keepThis) {
                val currentKept = keptTriangles.incrementAndGet()
                // ابعت اول دفعة
                if (currentKept >= PROGRESSIVE_FIRST_BATCH && firstBatchSent.compareAndSet(0, 1)) {
                    val firstModel = STLModel(
                        vertices.copyOf(vIdx.get()),
                        normals.copyOf(vIdx.get()),
                        currentKept,
                        floatArrayOf(globalMinX, globalMinY, globalMinZ),
                        floatArrayOf(globalMaxX, globalMaxY, globalMaxZ),
                        true
                    )
                    withContext(Dispatchers.Main) { onFirstBatchReady(firstModel) }
                }
            }

            val currentProgress = progressCounter.incrementAndGet()
            if (currentProgress % progressStep == 0) {
                val percent = ((currentProgress * 90L) / totalTriangles).toInt()
                withContext(Dispatchers.Main) { onProgress(percent) }
            }
        }
        inputStream.close()
    }
}