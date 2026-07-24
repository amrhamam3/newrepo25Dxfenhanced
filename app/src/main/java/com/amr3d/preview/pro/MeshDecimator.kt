package com.amr3d.preview.pro

import kotlin.math.ceil
import kotlin.math.cbrt
import kotlin.math.sqrt

/**
 * تبسيط حقيقي لعدد المثلثات في الموديل الصلب (مش بس كثافة خطوط الـ Wireframe
 * الزخرفية زي القديم) — البند 1.2.
 *
 * ليه "Grid-based Vertex Clustering" بالذات (مش توحيد رؤوس متطابقة Vertex
 * Deduplication)؟ ملفاتنا الحقيقية سكانر ثلاثي الأبعاد (عضوية/منحنية)، والرؤوس
 * فيها بتبقى نادرًا متطابقة رقميًا (bit-identical) حتى لو النقط قريبة من بعضها
 * جدًا في الواقع، فتوحيد الرؤوس المتطابقة تمامًا مش هيقلل حجم حقيقي يُذكر.
 * الطريقة هنا بتقسّم صندوق الموديل لخلايا شبكة (Grid Cells)، وأي رؤوس واقعة في
 * نفس الخلية بيتم "دمجها" في نقطة واحدة (مركز الكتلة/centroid بتاعها) — فأي
 * مثلثات صغيرة متجاورة كل رؤوسها وقعت في خلايا متطابقة بتتحول لمثلث واحد أو
 * بتختفي تمامًا (تندمج). ده فعليًا "Mesh Decimation/LOD" حقيقي بيقلل عدد
 * المثلثات المرسومة الفعلي، مش مجرد تقليل كثافة خطوط عرض.
 *
 * الخوارزمية شغالة مباشرة على بيانات "Flat/Non-indexed" (كل مثلث بنقطه
 * الخاصة، من غير فهرسة) اللي التطبيق ده مبني عليها من الأصل — من غير الحاجة
 * لبناء بنية Half-edge/Indexed كاملة (اللي كانت هتاخد وقت ومساحة إضافية).
 *
 * ⚠️ ملحوظة مهمة عن الأبعاد: minBounds/maxBounds (الأبعاد الخارجية الحقيقية
 * للقطعة، المستخدمة في تقرير الفحص وأدوات القياس) بتتحفظ زي ما هي من الموديل
 * الأصلي عمدًا وما بتتغيرش بعد التبسيط — بس التفاصيل الداخلية (المثلثات
 * الصغيرة) هي اللي بتتبسط. يعني قياسات الطول/العرض/الارتفاع الكلية تفضل دقيقة
 * 100% حتى بعد التبسيط.
 */
object MeshDecimator {

    /** موديلات أصغر من كده مفيش داعي نبسطها أصلاً — التبسيط هيوفر حاجة ضئيلة
     * وممكن يشوّه التفاصيل في موديلات صغيرة أهم من إنه يوفر أداء. */
    private const val MIN_TRIANGLES_TO_DECIMATE = 20_000

    /** نسبة المثلثات المطلوب الاحتفاظ بيها حسب "جودة العرض" العامة من الإعدادات.
     * qualityLevel: 0=منخفضة، 1=متوسطة، 2=عالية (نفس ترقيم STLRenderer.qualityLevel) */
    fun keepRatioForQuality(qualityLevel: Int): Float = when (qualityLevel) {
        0 -> 0.25f // منخفضة — تبسيط قوي
        1 -> 0.50f // متوسطة — تبسيط معتدل
        else -> 1.0f // عالية — من غير تبسيط تلقائي (إلا لو فُرض بسبب ملف كبير)
    }

    /** النسبة الافتراضية لما المستخدم يوافق على تبسيط ملف كبير من رسائل التنبيه
     * (البند 1.1)، حتى لو إعداد الجودة الحالي "عالي" — نسبة معتدلة وآمنة، مش عدوانية. */
    const val FORCED_LARGE_FILE_KEEP_RATIO = 0.5f

    /**
     * بيرجع موديل جديد بعدد مثلثات أقل (أو نفس الموديل من غير تغيير لو التبسيط
     * مش لازم). آمنة تتنادى من أي Thread (خيط IO/Default)، مش لازم GL thread.
     */
    fun decimate(model: STLModel, keepRatio: Float): STLModel {
        if (keepRatio >= 0.999f) return model
        if (model.triangleCount < MIN_TRIANGLES_TO_DECIMATE) return model

        val verts = model.vertices
        val triangleCount = model.triangleCount

        val minX = model.minBounds[0]; val minY = model.minBounds[1]; val minZ = model.minBounds[2]
        val maxX = model.maxBounds[0]; val maxY = model.maxBounds[1]; val maxZ = model.maxBounds[2]
        val sizeX = (maxX - minX).let { if (it > 1e-6f) it else 1f }
        val sizeY = (maxY - minY).let { if (it > 1e-6f) it else 1f }
        val sizeZ = (maxZ - minZ).let { if (it > 1e-6f) it else 1f }

        val targetTriangles = maxOf(1000, (triangleCount * keepRatio).toInt())
        // لسطح مغلق مثلث (manifold triangulated)، عدد الرؤوس الفريدة تقريبًا نص
        // عدد المثلثات (V ≈ F/2) — بنستخدمها كتقدير لعدد خلايا الشبكة المطلوب
        val targetVertices = maxOf(300, targetTriangles / 2)
        val cellsPerAxis = maxOf(4, ceil(cbrt(targetVertices.toDouble())).toInt())

        val cellSizeX = sizeX / cellsPerAxis
        val cellSizeY = sizeY / cellsPerAxis
        val cellSizeZ = sizeZ / cellsPerAxis

        fun cellKey(x: Float, y: Float, z: Float): Long {
            val ix = ((x - minX) / cellSizeX).toInt().coerceIn(0, cellsPerAxis - 1)
            val iy = ((y - minY) / cellSizeY).toInt().coerceIn(0, cellsPerAxis - 1)
            val iz = ((z - minZ) / cellSizeZ).toInt().coerceIn(0, cellsPerAxis - 1)
            return (ix.toLong() shl 42) or (iy.toLong() shl 21) or iz.toLong()
        }

        // ── خطوة 1: تجميع كل رأس على حسب خلية الشبكة اللي واقع فيها، وحساب مجموع
        // المواقع في كل خلية (تمهيدًا لحساب مركز الكتلة/centroid) ──
        val sumX = HashMap<Long, Double>(targetVertices * 2)
        val sumY = HashMap<Long, Double>(targetVertices * 2)
        val sumZ = HashMap<Long, Double>(targetVertices * 2)
        val counts = HashMap<Long, Int>(targetVertices * 2)

        var i = 0
        while (i < verts.size) {
            val x = verts[i]; val y = verts[i + 1]; val z = verts[i + 2]
            val key = cellKey(x, y, z)
            sumX[key] = (sumX[key] ?: 0.0) + x
            sumY[key] = (sumY[key] ?: 0.0) + y
            sumZ[key] = (sumZ[key] ?: 0.0) + z
            counts[key] = (counts[key] ?: 0) + 1
            i += 3
        }

        val centroids = HashMap<Long, FloatArray>(counts.size)
        for ((key, n) in counts) {
            centroids[key] = floatArrayOf(
                (sumX[key]!! / n).toFloat(),
                (sumY[key]!! / n).toFloat(),
                (sumZ[key]!! / n).toFloat()
            )
        }

        // ── خطوة 2: نمرّ على كل مثلث، نستبدل رؤوسه الثلاثة بمراكز الخلايا اللي
        // وقعوا فيها. لو اتنين أو أكتر من رؤوس نفس المثلث وقعوا في نفس الخلية،
        // يبقى المثلث ده "انهار" (اندمج مع جيرانه) ومبيتحطش في المخرجات — دي هي
        // نقطة "دمج المثلثات الصغيرة المتجاورة" الفعلية. ──
        // ⚠️ حجم البفر بيتحسب حسب الهدف المتوقع (keepRatio) + هامش أمان 30%، مش
        // حسب أقصى حجم ممكن نظريًا (عدد المثلثات الأصلي كامل) — عشان نقلل ذروة
        // استهلاك الذاكرة المؤقتة أثناء التبسيط نفسه (الأولوية القصوى: الملف
        // لازم يفتح، حتى لو ده معناه سقف صارم شوية على التفاصيل في حالات نادرة)
        val targetCapacity = maxOf(1000, (triangleCount * keepRatio * 1.3f).toInt())
            .coerceAtMost(triangleCount)
        val outVerts = FloatArray(targetCapacity * 9)
        val outNorms = FloatArray(targetCapacity * 9)
        var w = 0

        var t = 0
        var vi = 0
        while (t < triangleCount && (w / 9) < targetCapacity) {
            val base = vi
            val k0 = cellKey(verts[base], verts[base + 1], verts[base + 2])
            val k1 = cellKey(verts[base + 3], verts[base + 4], verts[base + 5])
            val k2 = cellKey(verts[base + 6], verts[base + 7], verts[base + 8])

            if (k0 != k1 && k1 != k2 && k0 != k2) {
                val p0 = centroids.getValue(k0)
                val p1 = centroids.getValue(k1)
                val p2 = centroids.getValue(k2)

                // نورمال مسطّح جديد محسوب من الهندسة الفعلية بعد الدمج (النورمال
                // القديم لم يعد يمثّل السطح الجديد بدقة)
                val ux = p1[0] - p0[0]; val uy = p1[1] - p0[1]; val uz = p1[2] - p0[2]
                val vx = p2[0] - p0[0]; val vy = p2[1] - p0[1]; val vz = p2[2] - p0[2]
                var nx = uy * vz - uz * vy
                var ny = uz * vx - ux * vz
                var nz = ux * vy - uy * vx
                val len = sqrt((nx * nx + ny * ny + nz * nz).toDouble()).toFloat()
                if (len > 1e-12f) { nx /= len; ny /= len; nz /= len }

                outVerts[w] = p0[0]; outVerts[w + 1] = p0[1]; outVerts[w + 2] = p0[2]
                outVerts[w + 3] = p1[0]; outVerts[w + 4] = p1[1]; outVerts[w + 5] = p1[2]
                outVerts[w + 6] = p2[0]; outVerts[w + 7] = p2[1]; outVerts[w + 8] = p2[2]

                outNorms[w] = nx; outNorms[w + 1] = ny; outNorms[w + 2] = nz
                outNorms[w + 3] = nx; outNorms[w + 4] = ny; outNorms[w + 5] = nz
                outNorms[w + 6] = nx; outNorms[w + 7] = ny; outNorms[w + 8] = nz

                w += 9
            }
            t++; vi += 9
        }

        val outTriCount = w / 9
        // لو التبسيط أدى لعدد مثلثات صفر أو ضئيل جدًا (نادر، بس ممكن يحصل مع
        // نسب عدوانية جدًا)، نرجع الموديل الأصلي كاحتياط بدل ما نعرض شكل فاسد
        if (outTriCount < 4) return model

        return model.copy(
            vertices = outVerts.copyOf(w),
            normals = outNorms.copyOf(w),
            triangleCount = outTriCount,
            isWatertightHint = (outTriCount % 2 == 0)
            // minBounds / maxBounds: بتتوارث زي ما هي من الموديل الأصلي عمدًا (شوف الملحوظة فوق)
        )
    }
}
