package uz.hunta.bot

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Настройки и состояние (по каждому аккаунту отдельно). */
object Store {
    private lateinit var p: SharedPreferences

    /** Безопасно вызывать сколько угодно раз и из Activity, и из сервиса. */
    fun init(c: Context) {
        if (!::p.isInitialized) p = c.applicationContext.getSharedPreferences("hbot", Context.MODE_PRIVATE)
    }

    fun s(k: String, d: String = ""): String = p.getString(k, d) ?: d
    fun i(k: String, d: Int): Int = s(k, d.toString()).trim().toIntOrNull() ?: d
    fun put(k: String, v: String) { p.edit().putString(k, v).apply() }

    /** Аккаунт, с которым бот работает прямо сейчас (в режиме «ВСЕ АККАУНТЫ»). */
    @Volatile var cur: String? = null

    fun acc(): String = cur ?: s("acc", "acc1").trim().ifEmpty { "acc1" }

    fun accounts(): List<String> =
        s("accounts").lines().map { it.trim().removePrefix("@").lowercase() }.filter { it.isNotEmpty() }.distinct()

    fun saveAccounts(l: List<String>) =
        put("accounts", l.map { it.trim().removePrefix("@").lowercase() }.filter { it.isNotEmpty() }.distinct().joinToString("\n"))

    fun addAccount(n: String): Boolean {
        val u = n.trim().removePrefix("@").lowercase()
        if (!Regex("^[a-z0-9._]{1,30}$").matches(u)) return false
        val l = accounts()
        if (u in l) return false
        saveAccounts(l + u)
        return true
    }

    fun removeAccount(a: String) {
        saveAccounts(accounts().filter { it != a })
        listOf("fl_", "fd_", "pause_", "blk_", "dd_", "tier_", "since_", "done_", "skipd_", "lu_", "off_",
            "h_follow_", "h_unfollow_").forEach { remove(it + a) }
        all().keys.filter { it.startsWith("st_${a}_") }.forEach { remove(it) }
    }

    /** Аккаунт включён (по умолчанию да). */
    fun enabled(a: String): Boolean = s("off_$a") != "1"
    fun setEnabled(a: String, on: Boolean) { if (on) remove("off_$a") else put("off_$a", "1") }

    /** Только включённые аккаунты — их берут режимы «ВСЕ» и «РАСПИСАНИЕ». */
    fun activeAccounts(): List<String> = accounts().filter { enabled(it) }

    fun followedCount(a: String): Int = s("fl_$a").lines().count { it.contains("|") }

    // белый список: этих ников бот никогда не отписывает
    fun whitelist(): Set<String> =
        s("whitelist").split("\n", ",", " ", ";").map { it.trim().removePrefix("@").lowercase() }
            .filter { it.isNotEmpty() }.toSet()

    fun remove(k: String) { p.edit().remove(k).apply() }
    fun all(): Map<String, *> = p.all

    // список «на кого подписался бот»: ник|время
    fun followed(): MutableList<Pair<String, Long>> =
        s("fl_${acc()}").lines().mapNotNull {
            val a = it.split("|")
            if (a.size == 2) a[0] to (a[1].toLongOrNull() ?: 0L) else null
        }.toMutableList()

    private fun saveFollowed(l: List<Pair<String, Long>>) =
        put("fl_${acc()}", l.joinToString("\n") { "${it.first}|${it.second}" })

    fun addFollowed(u: String) {
        val l = followed(); l.add(u to System.currentTimeMillis()); saveFollowed(l)
        put("fd_${acc()}", "${today()}|${followedToday() + 1}")
    }

    fun removeFollowed(u: String) = saveFollowed(followed().filter { it.first != u })

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun followedToday(): Int {
        val v = s("fd_${acc()}").split("|")
        return if (v.size == 2 && v[0] == today()) v[1].toIntOrNull() ?: 0 else 0
    }

    // часовые счётчики действий (по аккаунту): отметки времени за последний час
    private fun hourList(kind: String): MutableList<Long> {
        val now = System.currentTimeMillis()
        return s("h_${kind}_${acc()}").split(",").mapNotNull { it.toLongOrNull() }
            .filter { now - it < 3_600_000L }.toMutableList()
    }

    fun markHour(kind: String) {
        val l = hourList(kind); l.add(System.currentTimeMillis())
        put("h_${kind}_${acc()}", l.joinToString(","))
    }

    /** Сколько мс ждать, пока освободится место в часовом лимите (0 — можно действовать). */
    fun hourWait(kind: String, limit: Int): Long {
        val l = hourList(kind)
        if (l.size < limit) return 0L
        val oldest = l.minOrNull() ?: return 0L
        return maxOf(0L, oldest + 3_600_000L - System.currentTimeMillis()) + 1_000L
    }

    // особый аккаунт: свои доноры, остальные аккаунты берут общих
    fun special(): String = s("special").trim().removePrefix("@").lowercase()

    fun isSpecial(acc: String): Boolean {
        val sp = special()
        return sp.isNotEmpty() && sp == acc.trim().removePrefix("@").lowercase()
    }

    fun donorsFor(acc: String): List<String> =
        s(if (isSpecial(acc)) "donorsSpecial" else "donors").lines()
            .map { it.trim().removePrefix("@") }.filter { it.isNotEmpty() }

    private fun doneSet(acc: String): MutableSet<String> =
        s("dd_$acc").split(",").filter { it.isNotEmpty() }.toMutableSet()

    fun donorDone(acc: String, d: String): Boolean = d.lowercase() in doneSet(acc)

    fun markDonorDone(acc: String, d: String) {
        val x = doneSet(acc); x.add(d.lowercase()); put("dd_$acc", x.joinToString(","))
    }

    fun resetDonorsDone(acc: String) { put("dd_$acc", "") }

    // паузы после блокировок Instagram
    fun pausedUntil(acc: String): Long = s("pause_$acc", "0").toLongOrNull() ?: 0L

    fun blocks(acc: String): List<Long> = s("blk_$acc").split(",").mapNotNull { it.toLongOrNull() }

    fun lastBlock(acc: String): Long = blocks(acc).maxOrNull() ?: 0L

    fun pause(acc: String, ms: Long) {
        val now = System.currentTimeMillis()
        put("pause_$acc", (now + ms).toString())
        put("blk_$acc", (blocks(acc) + now).takeLast(50).joinToString(","))
    }

    fun clearPause(acc: String) { put("pause_$acc", "0") }

    fun lastUnfollow(): Long = s("lu_${acc()}", "0").toLongOrNull() ?: 0L
    fun markUnfollow() = put("lu_${acc()}", System.currentTimeMillis().toString())
}

/** Журнал: хранится в памяти и в файле, поэтому не пропадает после вылета. */
object BotLog {
    private var file: File? = null
    private val mem = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US)

    @Synchronized fun init(c: Context) {
        if (file != null) return
        val f = File(c.applicationContext.filesDir, "log.txt")
        file = f
        try {
            if (f.exists() && f.length() > 200_000) {
                val tail = f.readLines().takeLast(500)
                f.writeText(tail.joinToString("\n") + "\n")
            }
            if (f.exists()) f.readLines().takeLast(150).forEach { mem.addLast(it) }
        } catch (_: Exception) {}
    }

    @Synchronized fun add(s: String) {
        val line = "${fmt.format(Date())}  $s"
        mem.addLast(line)
        while (mem.size > 150) mem.removeFirst()
        try { file?.appendText(line + "\n") } catch (_: Exception) {}
    }

    @Synchronized fun text(): String = mem.reversed().joinToString("\n")

    @Synchronized fun clear() {
        mem.clear()
        try { file?.writeText("") } catch (_: Exception) {}
    }
}

/** Запись причин вылета в файл, чтобы их можно было посмотреть в приложении. */
object CrashHandler {
    private const val FILE = "crash.txt"

    fun install(c: Context) {
        val app = c.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            record(app, "вылет, поток ${t.name}", e)
            prev?.uncaughtException(t, e)
        }
    }

    fun record(c: Context, where: String, e: Throwable) {
        try {
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            File(c.applicationContext.filesDir, FILE).writeText("$ts  $where\n$sw")
            BotLog.add("СБОЙ ($where): ${e.javaClass.simpleName}: ${e.message}")
        } catch (_: Throwable) {}
    }

    fun last(c: Context): String = try {
        val f = File(c.applicationContext.filesDir, FILE)
        if (f.exists()) f.readText() else ""
    } catch (_: Throwable) { "" }

    fun clear(c: Context) { try { File(c.applicationContext.filesDir, FILE).delete() } catch (_: Throwable) {} }
}
