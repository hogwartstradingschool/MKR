package com.mkr.player

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer

// Video Grabber engine (yt-dlp + ffmpeg). Chhota rakha: link -> qualities -> download -> Movies/VideoGrabber
object Grab {
    class Tier(@JvmField val height: Int, @JvmField val chip: String, @JvmField val size: Long) // height: >0 p, 0 best, -1 audio
    class Info(@JvmField val title: String, @JvmField val tiers: List<Tier>)
    class Job(@JvmField val id: Int, @JvmField val url: String, @JvmField val height: Int, @JvmField val size: Long, @JvmField var title: String) {
        @Volatile @JvmField var status = "running"   // running | done | error | canceled
        @Volatile @JvmField var pct = 0.0
        @Volatile @JvmField var speed = 0.0
        @Volatile @JvmField var eta = 0.0
        @Volatile @JvmField var merging = false
        @Volatile @JvmField var err = ""
        @Volatile @JvmField var uri = ""
        @Volatile @JvmField var where = ""
        @Volatile @JvmField var pid = ""
    }

    private lateinit var app: Context
    private var attached = false
    private var inited = false
    private var seq = 0
    private val all = CopyOnWriteArrayList<Job>()
    private val cache = ConcurrentHashMap<String, Pair<Long, JSONObject>>()
    private const val EMB = "youtube:player_client=tv_embedded,web_embedded,tv"

    @JvmStatic fun jobs(): List<Job> = all
    @JvmStatic fun active(): Int = all.count { it.status == "running" }
    @JvmStatic fun done(): Int = all.count { it.status == "done" }
    @JvmStatic fun speedSum(): Double = all.filter { it.status == "running" }.sumOf { it.speed }
    @JvmStatic fun avgPct(): Int { val r = all.filter { it.status == "running" }; return if (r.isEmpty()) 0 else r.map { it.pct }.average().toInt() }

    // ---------- setup ----------
    @JvmStatic @Synchronized fun attach(c: Context) {
        if (attached) return
        app = c.applicationContext; attached = true
        Thread { runCatching { ensureInit() } }.start()   // pehle se garam: Python/yt-dlp unzip pehle hi ho jaye
    }

    @Synchronized private fun ensureInit() {
        if (inited) return
        YoutubeDL.getInstance().init(app)
        FFmpeg.getInstance().init(app)
        inited = true
        Thread {
            val sp = app.getSharedPreferences("grab", 0)
            if (System.currentTimeMillis() - sp.getLong("upd", 0L) < 12 * 3600_000L) return@Thread
            runCatching { Thread.sleep(20_000) }
            if (updateYtDlp()) sp.edit().putLong("upd", System.currentTimeMillis()).apply()
        }.start()
    }

    // yt-dlp ko latest karna (sites roz badalti hain); reflection => library version se compile nahi tootta
    private fun updateYtDlp(): Boolean {
        return runCatching {
            val y = YoutubeDL.getInstance()
            val m = y.javaClass.methods.first { it.name == "updateYoutubeDL" && it.parameterTypes.size == 2 }
            val ch = m.parameterTypes[1]
            var inst: Any? = null
            ch.declaredFields.firstOrNull { it.name.contains("STABLE") }?.let { it.isAccessible = true; inst = it.get(null) }
            if (inst == null) ch.declaredClasses.firstOrNull { it.simpleName.contains("STABLE") }?.let { inst = it.getDeclaredField("INSTANCE").get(null) }
            m.invoke(y, app, inst)
        }.isSuccess
    }

    private var lastForced = 0L
    private fun forceUpdate(): Boolean = synchronized(this) {
        val now = System.currentTimeMillis()
        if (now - lastForced < 600_000) return false
        lastForced = now
        updateYtDlp()
    }

    private fun ageGate(e: Exception): Boolean {
        val m = (e.message ?: "").lowercase()
        return m.contains("confirm your age") || m.contains("age-restricted") || m.contains("age restricted") ||
            m.contains("sign in to confirm") || m.contains("inappropriate for some users") || m.contains("age verification")
    }
    private fun stale(e: Exception): Boolean {
        val m = (e.message ?: "").lowercase()
        return m.contains("unable to extract") || m.contains("latest version") || m.contains("please report this issue")
    }
    private fun msg(e: Exception): String =
        (e.message ?: e.toString()).lines().lastOrNull { it.isNotBlank() }?.removePrefix("ERROR: ")?.take(200) ?: "Error"
    private fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k, "")

    // ---------- link -> qualities ----------
    private fun runInfo(url: String, emb: Boolean): JSONObject {
        val rq = YoutubeDLRequest(url)
        rq.addOption("--no-playlist"); rq.addOption("-J"); rq.addOption("--no-warnings")
        rq.addOption("--socket-timeout", "20"); rq.addOption("--extractor-retries", "2")
        if (emb) rq.addOption("--extractor-args", EMB)
        val out = YoutubeDL.getInstance().execute(rq, "info-" + System.nanoTime()) { _, _, _ -> }.out
        return JSONObject(out.substring(out.indexOf('{')))
    }

    private fun info(url: String): JSONObject {
        cache[url]?.let { if (System.currentTimeMillis() - it.first < 1_200_000) return it.second }
        ensureInit()
        val i = try { runInfo(url, false) } catch (e: Exception) {
            when {
                ageGate(e) -> runInfo(url, true)
                stale(e) && forceUpdate() -> runInfo(url, false)
                else -> throw e
            }
        }
        cache[url] = System.currentTimeMillis() to i
        if (cache.size > 10) cache.keys.firstOrNull { it != url }?.let { cache.remove(it) }
        return i
    }

    @JvmStatic fun fetch(url: String, ok: Consumer<Info>, fail: Consumer<String>) {
        Thread {
            try { ok.accept(tiers(info(url))) } catch (e: Exception) { fail.accept(msg(e)) }
        }.start()
    }

    private fun fsize(f: JSONObject, dur: Double): Long {
        val a = f.optLong("filesize", 0); if (a > 0) return a
        val b = f.optLong("filesize_approx", 0); if (b > 0) return b
        val tbr = f.optDouble("tbr", 0.0)
        return if (!tbr.isNaN() && tbr > 0 && dur > 0) (tbr * 125 * dur).toLong() else 0L
    }

    private fun chipOf(h: Int): String = when {
        h >= 1944 -> "4K"; h >= 1296 -> "2K"; h >= 972 -> "FHD"; h >= 648 -> "HD"; else -> "SD"
    }

    private fun tiers(info: JSONObject): Info {
        val fa = info.optJSONArray("formats") ?: JSONArray()
        val fm = (0 until fa.length()).mapNotNull { fa.optJSONObject(it) }
        val dur = info.optDouble("duration", 0.0).let { if (it.isNaN()) 0.0 else it }
        val vids = fm.filter { it.optInt("height", 0) > 0 && it.str("vcodec") != "none" }
        val auds = fm.filter { val a = it.str("acodec"); val v = it.str("vcodec"); a.isNotEmpty() && a != "none" && (v.isEmpty() || v == "none") }
        val aSize = auds.maxOfOrNull { fsize(it, dur) } ?: 0L
        val out = ArrayList<Tier>()
        var last = 0
        for ((h, g) in vids.groupBy { it.optInt("height") }.toSortedMap(reverseOrder<Int>())) {
            if (out.size >= 6) break
            if (last != 0 && h > last * 0.92) continue          // 8% se kam farq wali heights ek ginti hain
            val best = g.maxByOrNull { fsize(it, dur) }!!
            val size = fsize(best, dur) + (if (best.str("acodec") == "none") aSize else 0L)
            out.add(Tier(h, chipOf(h), size)); last = h
        }
        if (out.isEmpty()) out.add(Tier(0, "BEST", 0))
        if (auds.isNotEmpty()) out.add(Tier(-1, "MP3", aSize))
        return Info(info.optString("title", "video").ifBlank { "video" }, out)
    }

    // ---------- download ----------
    @JvmStatic fun start(url: String, height: Int, size: Long, title: String): Job {
        val j = synchronized(this) { Job(++seq, url, height, size, title).also { all.add(it) } }
        run(j)
        return j
    }
    @JvmStatic fun retry(j: Job) { if (j.status != "running") run(j) }
    @JvmStatic fun cancel(j: Job) {
        if (j.status != "running") return
        j.status = "canceled"; j.speed = 0.0
        stop(j)
    }
    @JvmStatic fun remove(j: Job) { if (j.status == "running") cancel(j); all.remove(j); File(app.cacheDir, "g/${j.id}").deleteRecursively() }

    private fun stop(j: Job) { runCatching { YoutubeDL.getInstance().destroyProcessById(j.pid) } }
    private fun dirBytes(d: File) = d.listFiles()?.sumOf { it.length() } ?: 0L

    private fun run(j: Job) {
        j.status = "running"; j.err = ""; j.speed = 0.0; j.eta = 0.0; j.merging = false; j.pct = 0.0
        runCatching { // chalte download ke liye notification service
            val i = Intent(app, GrabService::class.java)
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i)
        }
        Thread {
            val dir = File(app.cacheDir, "g/${j.id}").apply { mkdirs() }
            val pid = "g-${j.id}-${System.nanoTime()}"; j.pid = pid
            val mon = Thread {
                var last = dirBytes(dir)
                try {
                    while (j.status == "running") {
                        Thread.sleep(500)
                        val cur = dirBytes(dir); val inst = (cur - last) / 0.5; last = cur
                        if (!j.merging) {
                            j.speed = if (j.speed > 0) j.speed * 0.6 + inst * 0.4 else inst
                            if (j.size > 0) {
                                val tot = maxOf(j.size, cur)
                                j.pct = minOf(99.0, cur * 100.0 / tot)
                                if (j.speed > 1) j.eta = (tot - cur) / j.speed
                            }
                        }
                    }
                } catch (e: InterruptedException) { }
            }
            mon.isDaemon = true; mon.start()
            try {
                ensureInit()
                val h = j.height
                fun req(emb: Boolean): YoutubeDLRequest {
                    val rq = YoutubeDLRequest(j.url)
                    rq.addOption("--no-playlist"); rq.addOption("--no-warnings")
                    if (h < 0) {
                        rq.addOption("-f", "ba/b"); rq.addOption("-x"); rq.addOption("--audio-format", "m4a")
                    } else {
                        rq.addOption("-f", if (h > 0) "bv*[height<=$h]+ba/b[height<=$h]/b" else "bv*+ba/b")
                        rq.addOption("-S", "res,vcodec:h264,acodec:m4a")
                        rq.addOption("--merge-output-format", "mp4")
                    }
                    rq.addOption("-N", "16"); rq.addOption("--continue")
                    rq.addOption("--retries", "15"); rq.addOption("--fragment-retries", "15"); rq.addOption("--socket-timeout", "30")
                    if (emb) rq.addOption("--extractor-args", EMB)
                    rq.addOption("-o", dir.absolutePath + "/%(title).120B [%(id)s].%(ext)s")
                    return rq
                }
                fun go(emb: Boolean) = YoutubeDL.getInstance().execute(req(emb), pid) { p, eta, line ->
                    if (line.contains("[Merger]") || line.contains("[VideoRemuxer]") || line.contains("[ExtractAudio]")) {
                        j.merging = true; j.pct = 99.0; j.speed = 0.0; j.eta = 0.0
                    } else if (!j.merging && j.size <= 0) {
                        j.pct = minOf(99.0, p.toDouble().coerceAtLeast(0.0)); j.eta = eta.toDouble().coerceAtLeast(0.0)
                    }
                }
                try { go(false) } catch (e: Exception) { if (j.status == "running" && ageGate(e)) go(true) else throw e }
                if (j.status != "running") return@Thread
                val f = dir.listFiles()?.filter { !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") && !it.name.startsWith(".") && it.length() > 0 }
                    ?.maxByOrNull { it.length() } ?: throw IOException("File nahi bani")
                j.merging = true
                j.where = save(j, f)
                j.pct = 100.0; j.speed = 0.0; j.eta = 0.0; j.merging = false; j.status = "done"
                GrabService.notifyDone(app, "\u2705 Saved - " + j.where)
            } catch (e: Exception) {
                if (j.status == "running") {
                    j.status = "error"; j.speed = 0.0; j.merging = false; j.err = msg(e)
                    GrabService.notifyDone(app, "\u274C Download fail")
                }
            } finally {
                mon.interrupt()
                if (j.status == "done" || j.status == "canceled") dir.deleteRecursively()
            }
        }.start()
    }

    // ---------- save to gallery (Movies/VideoGrabber) -> MKR ki Videos list mein foran dikhti hai ----------
    private fun save(j: Job, f: File): String {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase())
        val audio = j.height < 0 || (mime ?: "").startsWith("audio")
        val mt = mime ?: if (audio) "audio/mp4" else "video/mp4"
        val rel = if (audio) "Music/VideoGrabber" else "Movies/VideoGrabber"
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, f.name); put(MediaStore.MediaColumns.MIME_TYPE, mt)
                put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
            }
            val coll = if (audio) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = app.contentResolver.insert(coll, cv) ?: throw IOException("MediaStore insert fail")
            app.contentResolver.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o, 1 shl 20) } }
            if (!audio) j.uri = uri.toString()
        } else {
            val d = File(Environment.getExternalStoragePublicDirectory(if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES), "VideoGrabber")
            d.mkdirs(); val t = File(d, f.name); f.copyTo(t, true)
            MediaScannerConnection.scanFile(app, arrayOf(t.absolutePath), null) { _, u -> if (u != null && !audio) j.uri = u.toString() }
        }
        return rel
    }
}
