package uz.hunta.bot

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Рабочее окно (часы «с» и «до») и вспомогательные функции времени.
 * Если «с» и «до» одинаковые — ограничений по времени нет.
 */
object Schedule {
    fun startH(): Int = Store.i("startHour", 10).coerceIn(0, 23)
    fun endH(): Int = Store.i("endHour", 22).coerceIn(0, 24)

    fun always(): Boolean = startH() == endH()

    // «ночной режим»: в эти часы бот не делает никаких действий
    fun nightOn(): Boolean = Store.s("nightOn", "1") == "1"
    fun nightFrom(): Int = Store.i("nightFrom", 23).coerceIn(0, 23)
    fun nightTo(): Int = Store.i("nightTo", 7).coerceIn(0, 23)

    private fun curHour(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

    fun hourInNight(h: Int): Boolean {
        if (!nightOn()) return false
        val f = nightFrom(); val t = nightTo()
        if (f == t) return false
        return if (f < t) h in f until t else (h >= f || h < t)
    }

    fun hourInWork(h: Int): Boolean {
        if (always()) return true
        val s = startH(); val e = endH()
        return if (s < e) h in s until e else (h >= s || h < e)
    }

    fun inNight(): Boolean = hourInNight(curHour())

    fun inWindow(): Boolean = !inNight() && hourInWork(curHour())

    private fun at(dayOffset: Int, hour: Int): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, dayOffset)
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun windowStart(dayOffset: Int): Long = at(dayOffset, startH())
    fun windowEnd(dayOffset: Int): Long = at(dayOffset, endH())

    fun todayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    fun dayKey(dayOffset: Int): String {
        val c = Calendar.getInstance(); c.add(Calendar.DAY_OF_YEAR, dayOffset)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.time)
    }
    fun dm(ms: Long): String = SimpleDateFormat("dd.MM HH:mm", Locale.US).format(Date(ms))
    fun hm(ms: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))
}
