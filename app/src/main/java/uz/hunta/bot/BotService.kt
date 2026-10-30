package uz.hunta.bot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.random.Random

class BotService : AccessibilityService() {

    companion object {
        @Volatile var instance: BotService? = null
        @Volatile var running = false
        private const val IG = "com.instagram.android"
        private const val DAY = 86_400_000L
        private const val CHANNEL = "hbot_run"
        private const val NOTIF_ID = 1
        private const val CHANNEL_ALERT = "hbot_alert"
        private const val CHANNEL_INFO = "hbot_info"
        private const val NOTIF_ALERT = 100
        const val ACTION_STOP = "uz.hunta.bot.STOP"
        const val DUMP_FILE = "dump.txt"
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null
    private var wl: PowerManager.WakeLock? = null
    private var wlWait: PowerManager.WakeLock? = null
    private var receiverOn = false

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            BotLog.add("Стоп нажат в уведомлении")
            stop()
        }
    }

    override fun onServiceConnected() {
        instance = this
        Store.init(this); BotLog.init(this)
        registerStop()
        BotLog.add(
            "Сервис подключён | ${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) | Instagram: ${igVersion()}"
        )
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() { BotLog.add("Система прервала сервис (onInterrupt)") }

    override fun onDestroy() {
        BotLog.add("Сервис остановлен системой (onDestroy)")
        instance = null
        try { if (receiverOn) unregisterReceiver(stopReceiver) } catch (_: Throwable) {}
        receiverOn = false
        scope.cancel(); release()
        super.onDestroy()
    }

    private fun igVersion(): String = try {
        packageManager.getPackageInfo(IG, 0).versionName ?: "?"
    } catch (_: Throwable) { "не установлен" }

    private fun registerStop() {
        if (receiverOn) return
        try {
            val f = IntentFilter(ACTION_STOP)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(stopReceiver, f, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(stopReceiver, f)
            receiverOn = true
        } catch (e: Throwable) { BotLog.add("Кнопка «Стоп» в уведомлении недоступна: ${e.message}") }
    }

    // ───────────── уведомление (бот не убивается системой + кнопка СТОП) ─────────────
    @Suppress("DEPRECATION")
    private fun foreground(text: String) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Работа бота", NotificationManager.IMPORTANCE_LOW))
            val stopIntent = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_STOP).setPackage(packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val openIntent = PendingIntent.getActivity(
                this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            val n = Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Бот работает")
                .setContentText(text)
                .setContentIntent(openIntent)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_delete, "СТОП", stopIntent)
                .build()
            startForeground(NOTIF_ID, n)
        } catch (e: Throwable) { BotLog.add("Не смог показать уведомление: ${e.message}") }
    }

    @Suppress("DEPRECATION")
    private fun background() {
        try { stopForeground(true) } catch (_: Throwable) {}
    }

    private fun modeTitle(mode: String) = when (mode) {
        "follow" -> "Подписки"
        "engage" -> "Активность"
        "unfollow" -> "Отписка"
        "all" -> "Все аккаунты по очереди"
        "sched" -> "Расписание"
        "browse" -> "Разнообразие (проверка)"
        "check" -> "Проверка аккаунтов"
        "auto" -> "Авто-режим"
        else -> mode
    }

    // ───────────── запуск / остановка ─────────────
    fun start(mode: String) {
        if (job?.isActive == true) { BotLog.add("Уже работает"); return }
        job = scope.launch {
            running = true; acquire(); foreground(modeTitle(mode))
            BotLog.add("▶ Старт: ${modeTitle(mode)}")
            try {
                if (mode in setOf("follow", "engage", "unfollow", "auto")) {
                    val until = Store.pausedUntil(Store.acc())
                    if (until > System.currentTimeMillis()) {
                        BotLog.add("@${Store.acc()} на паузе после блокировки до ${Schedule.dm(until)} — запуск отменён")
                        return@launch
                    }
                    if (!preflight()) return@launch
                }
                when (mode) {
                    "follow" -> follow()
                    "engage" -> engage()
                    "unfollow" -> unfollow()
                    "all" -> runAll()
                    "sched" -> runSchedule()
                    "browse" -> browseBreak(force = true)
                    "check" -> checkAccounts()
                    "auto" -> {
                        browseBreak(); follow(); browseBreak(); engage()
                        if (System.currentTimeMillis() - Store.lastUnfollow() > 7 * DAY) unfollow()
                        sendSummary()
                    }
                }
                BotLog.add("Готово")
            } catch (e: CancellationException) {
                BotLog.add("Остановлено")
            } catch (e: ActionBlocked) {
                blocked(Store.acc())
            } catch (e: Throwable) {
                BotLog.add("Ошибка: ${e.message ?: e.javaClass.simpleName}")
                CrashHandler.record(this@BotService, "ошибка в работе бота", e)
            } finally { running = false; Store.cur = null; release(); background() }
        }
    }

    fun stop() { job?.cancel() }

    @Suppress("DEPRECATION")
    private fun acquire() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wl = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "hbot:run")
            wl?.acquire(12 * 3600_000L)
        } catch (e: Throwable) { BotLog.add("Не смог удержать экран включённым: ${e.message}") }
    }
    private fun release() {
        try { if (wl?.isHeld == true) wl?.release() } catch (_: Throwable) {}
        try { if (wlWait?.isHeld == true) wlWait?.release() } catch (_: Throwable) {}
    }

    // ───────────── ДИАГНОСТИКА: снимок экрана Instagram ─────────────
    fun dumpScreen() {
        scope.launch {
            try {
                BotLog.add("Диагностика: открой нужный экран Instagram, снимок через 10 сек")
                delay(10_000)
                val sb = StringBuilder()
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                sb.append("Снимок экрана $ts\n")
                sb.append("Устройство: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
                sb.append("Instagram: ${igVersion()}\n")
                try {
                    val ws = windows
                    sb.append("Окон: ${ws.size}\n")
                    for (w in ws) sb.append("  окно type=${w.type} layer=${w.layer} active=${w.isActive} focused=${w.isFocused} title=${w.title}\n")
                } catch (e: Throwable) { sb.append("Окна недоступны: ${e.message}\n") }
                val root = rootInActiveWindow
                if (root == null) {
                    sb.append("\nrootInActiveWindow = null (Instagram не на экране или не отдаёт элементы)\n")
                } else {
                    sb.append("\nПакет: ${root.packageName}\n")
                    val counter = IntArray(1)
                    dumpNode(root, 0, sb, counter)
                    sb.append("\nВсего элементов: ${counter[0]}\n")
                }
                File(filesDir, DUMP_FILE).writeText(sb.toString())
                BotLog.add("Снимок экрана сохранён (${sb.length} символов). Открой Leongram → «Показать снимок»")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                BotLog.add("Снимок не получился: ${e.message ?: e.javaClass.simpleName}")
                CrashHandler.record(this@BotService, "снимок экрана", e)
            }
        }
    }

    private fun dumpNode(n: AccessibilityNodeInfo?, depth: Int, sb: StringBuilder, counter: IntArray) {
        if (n == null || depth > 40 || counter[0] >= 1500) return
        counter[0]++
        val r = Rect(); n.getBoundsInScreen(r)
        val flags = (if (n.isClickable) "C" else "") + (if (n.isLongClickable) "L" else "") +
            (if (n.isScrollable) "S" else "") + (if (n.isEditable) "E" else "") + (if (n.isSelected) "*" else "")
        sb.append("  ".repeat(minOf(depth, 20)))
            .append(n.className?.toString()?.substringAfterLast('.') ?: "?")
        n.viewIdResourceName?.let { sb.append(" id=").append(it.substringAfter(":id/")) }
        if (txt(n).isNotEmpty()) sb.append(" t='").append(txt(n).replace("\n", " ").take(80)).append("'")
        if (desc(n).isNotEmpty()) sb.append(" d='").append(desc(n).replace("\n", " ").take(80)).append("'")
        if (flags.isNotEmpty()) sb.append(" [").append(flags).append("]")
        sb.append(" ").append(r.toShortString()).append("\n")
        for (i in 0 until n.childCount) dumpNode(n.getChild(i), depth + 1, sb, counter)
    }

    /** Короткое описание того, что бот сейчас видит на экране (для журнала). */
    private fun visibleSummary(): String {
        if (rootInActiveWindow == null) return "экран Instagram недоступен"
        val items = all { txt(it).isNotEmpty() || desc(it).isNotEmpty() }
            .take(25).map { (txt(it).ifEmpty { desc(it) }).replace("\n", " ").take(30) }
        return if (items.isEmpty()) "ни одного подписанного элемента" else items.joinToString(" | ")
    }

    // ───────────── ОДИН АККАУНТ: переключиться и выполнить авто-режим ─────────────
    /**
     * Автопауза при блокировке: длительность берётся из настройки «Пауза после блокировки, часов».
     * Если предыдущая блокировка была меньше суток назад, пауза удваивается (максимум 72 часа).
     */
    private fun blocked(a: String) {
        val base = Store.i("pauseHours", 6).coerceIn(1, 72)
        val repeat = System.currentTimeMillis() - Store.lastBlock(a) < DAY
        val hours = if (repeat) minOf(72, base * 2) else base
        Store.pause(a, hours * 3_600_000L)
        val until = Schedule.dm(Store.pausedUntil(a))
        BotLog.add("⛔ @$a: Instagram ограничил действия. Пауза $hours ч (до $until)" +
            (if (repeat) ", повторная блокировка — пауза удвоена" else "") + ", потом 3 дня лимиты ×0.5")
        notifyBlock(a, hours, until)
    }

    private fun notifyBlock(a: String, hours: Int, until: String) =
        alert(a.hashCode() and 0xFFF, "⛔ @$a: Instagram ограничил действия",
            "Автопауза $hours ч, до $until. Аккаунт @$a пропускается, остальные работают. После паузы 3 дня лимиты ×0.5.", true)

    /** Обычное уведомление (не «живое» уведомление работы). high — со звуком и всплытием. */
    private fun alert(id: Int, title: String, text: String, high: Boolean = true) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val ch = if (high) CHANNEL_ALERT else CHANNEL_INFO
            nm.createNotificationChannel(
                if (high) NotificationChannel(ch, "Блокировки и ошибки", NotificationManager.IMPORTANCE_HIGH)
                else NotificationChannel(ch, "Итоги дня", NotificationManager.IMPORTANCE_LOW)
            )
            val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(this, ch)
                .setSmallIcon(if (high) android.R.drawable.ic_dialog_alert else android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text.lines().first())
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_ALERT + id, n)
        } catch (e: Throwable) { BotLog.add("Не смог показать уведомление: ${e.message}") }
    }

    /** Итоги дня одним уведомлением: по каждому аккаунту подписки, лайки, отписки, комментарии. */
    private fun sendSummary() {
        if (Store.s("dailySummary", "1") != "1") return
        val lines = (Store.accounts() + Store.acc()).distinct().mapNotNull { a ->
            val t = Stats.forDay(a, 0)
            if (t.sum() == 0) null else "@$a: подписок ${t[0]}, лайков ${t[1]}, отписок ${t[2]}, комм. ${t[3]}"
        }
        alert(4101, "📊 Итоги дня", if (lines.isEmpty()) "Сегодня действий не было" else lines.joinToString("\n"), false)
    }

    /** Проверка перед запуском: Instagram установлен, открывается и видны кнопки нижнего меню. */
    private suspend fun preflight(): Boolean {
        BotLog.add("Проверка перед запуском…")
        var problem: String? = null
        if (igVersion() == "не установлен") problem = "Instagram не установлен"
        else if (!openInstagram()) problem = "Instagram не открылся. Проверь: экран разблокирован, в MIUI включены «всплывающие окна в фоне»"
        else {
            delay(rnd(1500, 2500))
            var tries = 0
            while ((tabByDesc("Home") == null || profileTab() == null) && tries < 3) {
                if (!safeBack(1000)) break
                tries++
            }
            if (tabByDesc("Home") == null || profileTab() == null)
                problem = "не нашёл кнопки меню Instagram (Home / Profile). Возможные причины: язык не английский, открыт другой экран или Instagram обновился. Сделай «Снимок экрана Instagram»"
        }
        if (problem != null) {
            BotLog.add("❌ Проверка не пройдена: $problem")
            alert(4100, "Бот не запустился", problem)
            return false
        }
        BotLog.add("  проверка пройдена")
        return true
    }

    private suspend fun runAccountSession(a: String, allowSkip: Boolean = false) {
        val until = Store.pausedUntil(a)
        if (until > System.currentTimeMillis()) {
            BotLog.add("  @$a на паузе после блокировки до ${Schedule.dm(until)} — пропуск")
            return
        }
        if (allowSkip && skipToday(a)) {
            BotLog.add("  @$a: сегодня выходной (случайный пропуск дня) — как у живого человека")
            return
        }
        BotLog.add("  лимиты: ${Limits.describe(a)}")
        if (!preflight()) return
        if (!switchAccount(a)) {
            BotLog.add("Не смог переключиться на @$a — пропуск")
            alert(4102, "Не смог переключиться на @$a", "Аккаунт пропущен. Проверь, что ник есть в списке аккаунтов Instagram (кнопка «Проверить аккаунты»).")
            return
        }
        try {
            browseBreak(); follow(); browseBreak(); engage()
            if (System.currentTimeMillis() - Store.lastUnfollow() > 7 * DAY) unfollow()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ActionBlocked) {
            blocked(a)
        } catch (e: Exception) {
            BotLog.add("@$a: ${e.message} — перехожу к следующему")
        }
    }

    // ───────────── РАЗНООБРАЗИЕ: случайные «прогулки» по Instagram и выходные дни ─────────────
    /** Решение «пропустить день» принимается один раз в сутки на аккаунт; два выходных подряд не бывает. */
    private fun skipToday(a: String): Boolean {
        val pct = Store.i("skipDayPct", 10)
        if (pct <= 0) return false
        val today = Schedule.todayKey()
        val saved = Store.s("skipd_$a")
        if (saved.startsWith("$today:")) return saved.endsWith(":1")
        val yesterdaySkipped = Store.s("skipd_$a").startsWith("${Schedule.dayKey(-1)}:1")
        val skip = !yesterdaySkipped && Random.nextInt(100) < pct
        Store.put("skipd_$a", "$today:${if (skip) 1 else 0}")
        return skip
    }

    private fun tabByDesc(name: String): AccessibilityNodeInfo? =
        first { desc(it).equals(name, true) }
            ?: first { desc(it).startsWith(name, true) && !desc(it).contains("picture", true) && it.isClickable }

    /** Короткая «прогулка»: лента, рилсы, чужие истории. Шанс задаётся полем «разнообразие, %». */
    private suspend fun browseBreak(force: Boolean = false) {
        val pct = Store.i("diversityPct", 60)
        if (!force && Random.nextInt(100) >= pct) return
        if (!igOnScreen()) { BotLog.add("  разнообразие пропущено: Instagram не на экране"); return }
        val acts = listOf("feed", "reels", "stories").shuffled().take(if (force) 3 else Random.nextInt(1, 3))
        BotLog.add("  ↺ разнообразие: " + acts.joinToString(" + ") {
            when (it) { "feed" -> "лента"; "reels" -> "рилсы"; else -> "истории других" }
        })
        for (x in acts) {
            checkBlock(); checkWindow()
            when (x) {
                "feed" -> browseFeed()
                "reels" -> browseReels()
                else -> browseStories()
            }
            delay(rnd(2000, 5000))
        }
    }

    private suspend fun goHome(): Boolean {
        val h = tabByDesc("Home")
        if (h == null) { BotLog.add("  вкладка Home не найдена"); return false }
        click(h); delay(rnd(1800, 3000))
        return true
    }

    private suspend fun browseFeed() {
        if (!goHome()) return
        val n = Random.nextInt(5, 13)
        repeat(n) {
            checkBlock()
            delay(rnd(2500, 6000))
            if (Random.nextInt(100) < 12) delay(rnd(6000, 14000))   // иногда «зачитаться»
            swipeUp()
        }
        BotLog.add("  лента: прокручено $n")
    }

    private suspend fun browseReels() {
        val r = tabByDesc("Reels")
        if (r == null) { BotLog.add("  вкладка Reels не найдена"); return }
        click(r); delay(rnd(3000, 4500))
        val n = Random.nextInt(3, 8)
        repeat(n) {
            checkBlock()
            delay(rnd(4000, 12000))
            swipeUp(); delay(rnd(1200, 2500))
        }
        BotLog.add("  рилсы: просмотрено $n")
        goHome()
    }

    private suspend fun browseStories() {
        if (!goHome()) return
        val ring = first {
            val d = desc(it).lowercase()
            d.contains("story") && !d.contains("your story") && !d.contains("add to") && !d.contains("create")
        }
        if (ring == null) { BotLog.add("  чужих историй не видно"); return }
        click(ring); delay(rnd(2000, 3500))
        val n = Random.nextInt(3, 7)
        repeat(n) {
            delay(rnd(3000, 7000))
            tapXY(0.92f, 0.5f)
        }
        BotLog.add("  чужие истории: просмотрено $n")
        var tries = 0
        while (tabByDesc("Home") == null && tries < 3) { if (!safeBack(1000)) break; tries++ }
    }

    // ───────────── ВСЕ АККАУНТЫ ПО ОЧЕРЕДИ ─────────────
    private suspend fun runAll() {
        val list = Store.activeAccounts()
        if (list.isEmpty()) { BotLog.add("Нет включённых аккаунтов — добавь или включи их на экране «Аккаунты»"); return }
        for ((idx, a) in list.withIndex()) {
            if (!Schedule.inWindow()) { BotLog.add("Рабочее окно закончилось — остановка"); return }
            Store.cur = a
            BotLog.add("══ @$a (${idx + 1}/${list.size})")
            runAccountSession(a)
            delay(rnd(120_000, 300_000))   // пауза между аккаунтами 2–5 мин
        }
        sendSummary()
    }

    // ───────────── РАСПИСАНИЕ: аккаунты стартуют сами в разное время дня ─────────────
    private suspend fun runSchedule() {
        if (Schedule.startH() >= Schedule.endH()) {
            BotLog.add("Для расписания задай «Работать с» меньше «до» (например 10 и 22)"); return
        }
        while (true) {
            val list = Store.activeAccounts()
            if (list.isEmpty()) { BotLog.add("Нет включённых аккаунтов — добавь или включи их на экране «Аккаунты»"); return }
            val today = Schedule.todayKey()
            val now = System.currentTimeMillis()
            // аккаунты на паузе после блокировки в план не попадают — к ним вернёмся, когда пауза кончится
            val todo = list.filter { Store.s("done_$it") != today && Store.pausedUntil(it) <= now }.shuffled()
            val we = Schedule.windowEnd(0)
            if (todo.isNotEmpty() && now < we - 10 * 60_000L) {
                val from = maxOf(now + 60_000L, Schedule.windowStart(0))
                val slot = (we - from) / todo.size
                val plan = todo.mapIndexed { i, a ->
                    a to from + i * slot + Random.nextLong(0, maxOf(1L, (slot * 0.4).toLong()))
                }
                BotLog.add("План на сегодня: " + plan.joinToString(", ") { "@${it.first} ${Schedule.hm(it.second)}" })
                for ((a, t) in plan) {
                    waitLong(t - System.currentTimeMillis(), "@$a до ${Schedule.hm(t)}")
                    if (System.currentTimeMillis() > we) { BotLog.add("Рабочее окно закончилось"); break }
                    Store.cur = a
                    BotLog.add("══ @$a (по расписанию)")
                    foreground("Работает @$a")
                    acquire()
                    runAccountSession(a, allowSkip = true)
                    release()
                    // заблокированный аккаунт не отмечаем выполненным: после паузы он получит новую попытку
                    if (Store.pausedUntil(a) <= System.currentTimeMillis()) Store.put("done_$a", today)
                    Store.cur = null
                }
            }
            val now2 = System.currentTimeMillis()
            val retry = list.filter { Store.s("done_$it") != today && Store.pausedUntil(it) > now2 }
                .map { Store.pausedUntil(it) }.minOrNull()
            if (retry != null && retry < Schedule.windowEnd(0) - 10 * 60_000L) {
                BotLog.add("Есть аккаунты на паузе — вернусь к ним после ${Schedule.hm(retry)}")
                waitLong(retry - now2 + rnd(60_000, 600_000), "конец паузы ${Schedule.hm(retry)}")
                continue
            }
            sendSummary()
            val next = Schedule.windowStart(1) + Random.nextLong(0, 40 * 60_000L)
            BotLog.add("На сегодня всё. Следующий запуск завтра около ${Schedule.hm(next)}")
            waitLong(next - System.currentTimeMillis(), "завтра около ${Schedule.hm(next)}")
        }
    }

    /** Долгое ожидание: экран можно выключить, но процессор не засыпает (иначе таймер собьётся). */
    private suspend fun waitLong(ms: Long, label: String) {
        if (ms <= 0) return
        release()
        foreground("Ждёт: $label")
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wlWait = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hbot:wait")
            wlWait?.acquire(ms + 60_000L)
        } catch (e: Throwable) { BotLog.add("Не смог удержать процессор в ожидании: ${e.message}") }
        try { delay(ms) } finally {
            try { if (wlWait?.isHeld == true) wlWait?.release() } catch (_: Throwable) {}
        }
    }

    /** Рабочее окно по часам. Вне окна действия не выполняются. */
    private fun checkWindow() {
        if (Schedule.inNight())
            throw IllegalStateException("сейчас ночной режим (${Schedule.nightFrom()}:00–${Schedule.nightTo()}:00) — действий нет")
        if (!Schedule.inWindow())
            throw IllegalStateException("сейчас вне рабочего окна ${Schedule.startH()}:00–${Schedule.endH()}:00 (чтобы убрать ограничение, поставь одинаковые «с» и «до»)")
    }

    /** true — можно действовать. false — часовой лимит исчерпан, а ждать нельзя (режим «по очереди»/расписание). */
    private suspend fun hourGate(kind: String, limit: Int): Boolean {
        while (true) {
            val w = Store.hourWait(kind, limit)
            if (w <= 0) return true
            if (Store.cur != null) {
                BotLog.add("  часовой лимит ($limit/час) исчерпан — этот аккаунт на сегодня пока всё")
                return false
            }
            BotLog.add("  часовой лимит ($limit/час): жду ${w / 60_000 + 1} мин")
            delay(w + rnd(5_000, 60_000))
            checkWindow()
        }
    }

    private fun profileTab() = first {
        val d = desc(it)
        d.equals("Profile", true) || (d.startsWith("Profile", true) && !d.contains("picture", true) && it.isClickable)
    }

    private fun launchIg(i: Intent): Boolean = try {
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: Throwable) {
        BotLog.add("  не смог запустить Instagram: ${e.message}"); false
    }

    // ───────────── поиск ника: по text И contentDescription, «содержит» с границами слова ─────────────
    private fun nickRegex(u: String): Regex =
        Regex("(^|[^a-z0-9._])" + Regex.escape(u.lowercase().removePrefix("@")) + "($|[^a-z0-9._])")

    private fun hasNick(n: AccessibilityNodeInfo, re: Regex): Boolean =
        re.containsMatchIn(txt(n).lowercase()) || re.containsMatchIn(desc(n).lowercase())

    private fun findNick(u: String): AccessibilityNodeInfo? {
        val re = nickRegex(u)
        return first { hasNick(it, re) }
    }

    /** Ищет ник, при необходимости пролистывая список аккаунтов. */
    private suspend fun findNickScrolling(u: String, rounds: Int = 4): AccessibilityNodeInfo? {
        repeat(rounds) { i ->
            findNick(u)?.let { return it }
            if (i < rounds - 1) { swipeUp(); delay(rnd(900, 1500)) }
        }
        return null
    }

    private suspend fun openInstagram(): Boolean {
        val launch = packageManager.getLaunchIntentForPackage(IG)
        if (launch == null) { BotLog.add("  Instagram не установлен"); return false }
        if (!launchIg(launch)) return false
        delay(rnd(3500, 5500))
        if (!igOnScreen()) {
            delay(3000)
            if (!igOnScreen()) {
                BotLog.add("  Instagram не открылся. MIUI: Настройки → Приложения → (это приложение) → Другие разрешения → «Показывать всплывающие окна, когда приложение работает в фоне» ВКЛ")
                return false
            }
        }
        return true
    }

    private suspend fun goToProfileTab(): AccessibilityNodeInfo? {
        var tab = profileTab(); var n = 0
        while (tab == null && n < 2) { if (!safeBack()) break; tab = profileTab(); n++ }
        if (tab == null) BotLog.add("  не вижу вкладку Profile. На экране: ${visibleSummary()}")
        return tab
    }

    /** Долгое нажатие на вкладку Profile -> список аккаунтов. */
    private suspend fun longPressProfile(tab: AccessibilityNodeInfo) {
        val r = Rect(); (profileTab() ?: tab).getBoundsInScreen(r)
        gesture(Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }, 900)
        delay(rnd(1800, 2800))
    }

    private suspend fun switchAccount(u: String): Boolean {
        if (!openInstagram()) return false
        val tab = goToProfileTab() ?: return false

        click(tab); delay(rnd(2000, 3000))
        if (findNick(u) != null) { BotLog.add("  уже в @$u"); return true }

        longPressProfile(tab)
        var row = findNickScrolling(u)
        if (row == null) {
            // запасной путь: кнопка переключения аккаунтов в шапке профиля
            val sw = first { desc(it).contains("switch", true) && it.isClickable }
            if (sw != null && click(sw)) { delay(rnd(1800, 2800)); row = findNickScrolling(u) }
        }
        if (row == null) {
            BotLog.add("  @$u нет в списке аккаунтов. На экране: ${visibleSummary()}")
            safeBack(); return false
        }
        click(row); delay(rnd(4000, 6000))

        profileTab()?.let { click(it) }; delay(rnd(2000, 3000))
        val ok = findNick(u) != null
        BotLog.add(if (ok) "  переключился на @$u" else "  не подтвердил вход в @$u. На экране: ${visibleSummary()}")
        return ok
    }

    // ───────────── ПРОВЕРКА АККАУНТОВ: открывает список и отмечает, какие ники нашёл ─────────────
    private suspend fun checkAccounts() {
        val list = Store.accounts()
        if (list.isEmpty()) { BotLog.add("Впиши ники в поле «ВСЕ аккаунты»"); return }
        BotLog.add("Проверка: ищу ${list.size} ник(ов) в списке аккаунтов Instagram")
        if (!openInstagram()) return
        val tab = goToProfileTab() ?: return
        click(tab); delay(rnd(2000, 3000))
        longPressProfile(tab)

        val found = LinkedHashSet<String>()
        for (round in 0 until 5) {
            for (u in list) if (u !in found && findNick(u) != null) found.add(u)
            if (found.size == list.size) break
            swipeUp(); delay(rnd(900, 1500))
        }
        for (u in list) BotLog.add(if (u in found) "  ✅ @$u найден" else "  ❌ @$u не найден")
        BotLog.add("Найдено ${found.size} из ${list.size}")
        if (found.size < list.size) BotLog.add("Если список не открылся или ники другие — сделай «Снимок экрана Instagram» на открытом списке и пришли мне. Сейчас на экране: ${visibleSummary()}")
        safeBack()
    }

    // ───────────── ПОДПИСКИ ─────────────
    private suspend fun follow() {
        val limit = Limits.followDay(Store.acc())
        val hourLimit = Limits.followHour(Store.acc())
        val acc = Store.acc()
        val special = Store.isSpecial(acc)
        val donors = Store.donorsFor(acc).filter { !(special && Store.donorDone(acc, it)) }
        if (donors.isEmpty()) {
            BotLog.add(
                if (special) "Особый аккаунт @$acc: нет непройденных доноров — впиши следующий ник в поле «Доноры особого аккаунта»"
                else "Укажи аккаунты-доноры"
            )
            return
        }
        if (special) BotLog.add("Особый аккаунт @$acc: доноры по порядку: " + donors.joinToString(", ") { "@$it" })
        val idleMax = if (special) 150 else 6
        var done = Store.followedToday()
        BotLog.add("Подписки: сегодня $done из $limit")
        val known = Store.followed().map { it.first }.toMutableSet()

        for (d in (if (special) donors else donors.shuffled())) {
            if (done >= limit) break
            BotLog.add("Донор @$d")
            openProfile(d)
            val f = first { txt(it).contains("followers", true) || desc(it).contains("followers", true) }
            if (f == null) { BotLog.add("  не нашёл «followers»"); continue }
            click(f); delay(rnd(2500, 4000))

            var idle = 0; var same = 0; var lastSig = ""; var exhausted = false
            while (done < limit && idle < idleMax) {
                checkBlock(); checkWindow()
                var acted = false
                for (b in all { txt(it) == "Follow" }) {
                    if (done >= limit) break
                    if (!hourGate("follow", hourLimit)) return
                    val u = usernameNear(b)
                    if (u == null || u in known) continue
                    if (click(b)) {
                        done++; acted = true; known.add(u); Store.addFollowed(u); Store.markHour("follow"); Stats.inc(Store.acc(), Stats.FOLLOW)
                        BotLog.add("  подписка @$u ($done/$limit)")
                        delay(rnd(25_000, 70_000))
                        bigPause()
                    }
                }
                idle = if (acted) 0 else idle + 1
                swipeUp(); delay(rnd(1500, 3000))
                if (special) {
                    // конец списка подписчиков: экран перестал меняться после прокрутки
                    val sig = all { txt(it).isNotEmpty() }.take(60).joinToString("|") { txt(it) }
                    same = if (sig == lastSig) same + 1 else 0
                    lastSig = sig
                    if (same >= 3) { exhausted = true; break }
                }
            }
            if (special && exhausted) {
                Store.markDonorDone(acc, d)
                BotLog.add("  донор @$d пройден до конца — дальше следующий из списка")
            }
        }
    }

    // ───────────── АКТИВНОСТЬ (только на тех, на кого подписан бот) ─────────────
    private suspend fun engage() {
        val list = Store.followed().map { it.first }.shuffled()
        if (list.isEmpty()) { BotLog.add("Список подписок пуст — сначала сделай подписки"); return }
        val likeLimit = Limits.likes(Store.acc())
        val perProfile = Store.i("likesPerProfile", 2).coerceAtLeast(1)
        val stories = Store.i("storiesPerProfile", 5)
        val cPct = Store.i("commentPct", 20)
        val comments = Store.s("comments").lines().map { it.trim() }.filter { it.isNotEmpty() }
        val deadline = System.currentTimeMillis() + Store.i("maxMin", 30) * 60_000L
        var liked = 0

        for (u in list) {
            if (liked >= likeLimit || System.currentTimeMillis() > deadline) break
            checkWindow()
            BotLog.add("@$u")
            openProfile(u)
            checkBlock()
            if (byText("Following") == null) { BotLog.add("  не в подписках — пропуск"); continue }
            watchStories(stories)
            ensureProfile()
            liked += likePosts(minOf(perProfile, likeLimit - liked), cPct, comments)
            ensureProfile()
            delay(rnd(8_000, 20_000))
            bigPause()
        }
        BotLog.add("Лайков за сессию: $liked")
    }

    private suspend fun watchStories(n: Int) {
        if (n <= 0) return
        val av = first { desc(it).contains("story", true) }
        if (av == null) { BotLog.add("  историй нет"); return }
        click(av); delay(rnd(2000, 3500))
        var seen = 0
        repeat(n) {
            delay(rnd(2500, 6500)); seen++
            if (byText("Following") != null) return@repeat   // уже вышли в профиль
            tapXY(0.92f, 0.5f)
        }
        BotLog.add("  истории: $seen")
    }

    private suspend fun likePosts(n: Int, cPct: Int, comments: List<String>): Int {
        if (n <= 0) return 0
        val post = first { desc(it).contains("Row 1, Column 1", true) }
            ?: first { desc(it).contains("Column 1", true) }
        if (post == null) { BotLog.add("  постов нет"); return 0 }
        click(post); delay(rnd(2500, 4000))
        var done = 0; var tries = 0
        while (done < n && tries < n + 3) {
            tries++
            checkBlock()
            val like = first { desc(it).equals("Like", true) }
            if (like != null && click(like)) {
                done++; Stats.inc(Store.acc(), Stats.LIKE); BotLog.add("  лайк")
                delay(rnd(1500, 3500))
                if (comments.isNotEmpty() && Random.nextInt(100) < cPct) comment(comments.random())
            }
            swipeUp(); delay(rnd(2000, 4500))
        }
        safeBack(1500)
        return done
    }

    private suspend fun comment(base: String) {
        val emoji = listOf("🔥", "✨", "👏", "😍", "💯", "")
        val text = (base + " " + emoji.random()).trim()
        val c = first { desc(it).equals("Comment", true) } ?: return
        click(c); delay(rnd(2000, 3500))
        val field = first { it.className == "android.widget.EditText" }
        if (field == null) { safeBack(); return }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        delay(rnd(1500, 3000))
        val send = first { txt(it).equals("Post", true) || desc(it).equals("Post", true) }
        if (send != null && click(send)) { Stats.inc(Store.acc(), Stats.COMMENT); BotLog.add("  комментарий: $text") }
        delay(rnd(1500, 2500))
        safeBack(1000)
        if (first { it.className == "android.widget.EditText" } != null) safeBack(1000)
    }

    // ───────────── ОТПИСКА (самых старых, не младше 2 дней) ─────────────
    private suspend fun unfollow() {
        val n = Limits.unfollowWeek(Store.acc())
        val hourLimit = Limits.unfollowHour(Store.acc())
        val now = System.currentTimeMillis()
        val wl = Store.whitelist()
        val targets = Store.followed().sortedBy { it.second }
            .filter { now - it.second > 2 * DAY && it.first.lowercase() !in wl }.take(n)
        if (targets.isEmpty()) { BotLog.add("Некого отписывать (белый список: ${wl.size})"); return }
        BotLog.add("Отписка: ${targets.size} шт.")
        var done = 0
        for ((u, _) in targets) {
            checkWindow()
            if (!hourGate("unfollow", hourLimit)) break
            openProfile(u)
            checkBlock()
            val b = byText("Following")
            if (b == null) {
                if (byText("Follow") != null || byText("Follow back") != null) Store.removeFollowed(u)
                continue
            }
            click(b); delay(rnd(1500, 2500))
            val un = byText("Unfollow")
            if (un != null && click(un)) {
                done++; Store.removeFollowed(u); Store.markHour("unfollow"); Stats.inc(Store.acc(), Stats.UNFOLLOW)
                BotLog.add("  отписка @$u ($done/${targets.size})")
                delay(rnd(1500, 2500))
            } else safeBack(0)
            delay(rnd(20_000, 50_000))
            bigPause()
        }
        Store.markUnfollow()
    }

    // ───────────── навигация / поиск узлов ─────────────
    private fun igOnScreen(): Boolean = rootInActiveWindow?.packageName?.toString() == IG

    /** «Назад» нажимается только если Instagram на экране — иначе бот не вылетает из чужих приложений. */
    private suspend fun safeBack(pause: Long = 1200): Boolean {
        if (!igOnScreen()) { BotLog.add("  Instagram не на экране — «Назад» не нажимаю"); return false }
        performGlobalAction(GLOBAL_ACTION_BACK)
        if (pause > 0) delay(pause)
        return true
    }

    private suspend fun openProfile(u: String) {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/_u/$u"))
            .setPackage(IG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try { startActivity(i) } catch (e: Throwable) {
            throw IllegalStateException("не смог открыть профиль @$u: ${e.message}")
        }
        delay(rnd(3500, 5500))
        if (!igOnScreen()) {
            delay(3000)
            if (!igOnScreen())
                throw IllegalStateException("Instagram не открылся (или ты вышел из него). MIUI: разреши всплывающие окна в фоне")
        }
    }

    private suspend fun ensureProfile() {
        repeat(3) {
            if (byText("Following") != null) return
            if (!safeBack()) return
        }
    }

    private val blockPhrases = listOf(
        "try again later", "action blocked", "we restrict certain activity",
        "we limit how often", "temporarily blocked", "temporarily restricted"
    )

    private fun checkBlock() {
        val hit = first { n ->
            val t = txt(n).lowercase(); val d = desc(n).lowercase()
            blockPhrases.any { t.contains(it) || d.contains(it) }
        }
        if (hit != null) throw ActionBlocked("Instagram ограничил действия — остановка")
    }

    private fun usernameNear(btn: AccessibilityNodeInfo): String? {
        val re = Regex("^[a-zA-Z0-9._]{1,30}$")
        val skip = setOf("follow", "following", "remove", "message")
        var p = btn.parent; var depth = 0
        while (p != null && depth < 4) {
            val ts = ArrayList<String>()
            fun walk(n: AccessibilityNodeInfo?) {
                if (n == null) return
                val t = txt(n); if (t.isNotEmpty()) ts.add(t)
                for (i in 0 until n.childCount) walk(n.getChild(i))
            }
            walk(p)
            ts.firstOrNull { re.matches(it) && it.lowercase() !in skip }?.let { return it.lowercase() }
            p = p.parent; depth++
        }
        return null
    }

    private fun all(pred: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        fun dfs(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 60) return
            if (pred(n)) out.add(n)
            for (i in 0 until n.childCount) dfs(n.getChild(i), depth + 1)
        }
        dfs(rootInActiveWindow, 0)
        return out
    }
    private fun first(pred: (AccessibilityNodeInfo) -> Boolean) = all(pred).firstOrNull()
    private fun txt(n: AccessibilityNodeInfo) = n.text?.toString() ?: ""
    private fun desc(n: AccessibilityNodeInfo) = n.contentDescription?.toString() ?: ""
    private fun byText(t: String) = first { txt(it).equals(t, true) }

    private fun click(n: AccessibilityNodeInfo?): Boolean {
        var c = n
        while (c != null && !c.isClickable) c = c.parent
        return c?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    // ───────────── жесты ─────────────
    /** Множитель темпа из настроек: 0 быстро, 1 обычно, 2 медленно, 3 очень медленно. */
    private fun speed(): Double = when (Store.s("speed", "1")) { "0" -> 0.7; "2" -> 1.5; "3" -> 2.2; else -> 1.0 }

    /** Случайная задержка. Темп масштабирует только «человеческие» паузы (от 5 сек), а не ожидание экранов. */
    private fun rnd(a: Long, b: Long): Long {
        val v = Random.nextLong(a, b)
        return if (b >= 5000L) (v * speed()).toLong() else v
    }

    /** Иногда делаем длинную паузу (1–4 мин), как будто человек отвлёкся. Шанс — в настройках. */
    private suspend fun bigPause() {
        val pct = Store.i("longPausePct", 8)
        if (pct <= 0 || Random.nextInt(100) >= pct) return
        val sec = Random.nextLong(60, 240)
        BotLog.add("  ☕ случайная пауза $sec сек")
        delay(sec * 1000)
    }

    private suspend fun gesture(p: Path, dur: Long) = suspendCancellableCoroutine<Unit> { c ->
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, dur)).build()
        val ok = dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { if (c.isActive) c.resume(Unit) }
            override fun onCancelled(d: GestureDescription?) { if (c.isActive) c.resume(Unit) }
        }, null)
        if (!ok && c.isActive) c.resume(Unit)
    }

    private suspend fun tapXY(fx: Float, fy: Float) {
        val m = resources.displayMetrics
        gesture(Path().apply { moveTo(m.widthPixels * fx, m.heightPixels * fy) }, 60)
    }

    private suspend fun swipeUp() {
        val m = resources.displayMetrics
        val x = m.widthPixels * Random.nextDouble(0.4, 0.6).toFloat()
        val p = Path().apply {
            moveTo(x, m.heightPixels * 0.75f)
            lineTo(x + Random.nextInt(-30, 30), m.heightPixels * 0.30f)
        }
        gesture(p, rnd(250, 450))
    }
}

/** Instagram показал «Try Again Later» / «Action Blocked» — аккаунт уходит на паузу. */
class ActionBlocked(msg: String) : IllegalStateException(msg)
