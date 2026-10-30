package uz.hunta.bot

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.WindowManager
import android.widget.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val fields = LinkedHashMap<String, EditText>()
    private lateinit var stats: TextView
    private lateinit var logView: TextView
    private val navCounters = ArrayList<Pair<Button, () -> String>>()
    private var noSave = false
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            stats.text = (if (BotService.instance == null) "⚠️ Спецвозможности ВЫКЛ" else if (BotService.running) "▶ РАБОТАЕТ" else "● Готов") +
                "\nАккаунтов: ${Store.accounts().size} (включено ${Store.activeAccounts().size}) · текущий: ${Store.acc()}" +
                "\nСегодня: подписок ${Store.followedToday()} · в списке бота: ${Store.followed().size}" +
                (if (Schedule.inNight()) "\n🌙 Ночной режим — бот не действует" else "")
            for (v in navCounters) v.first.text = v.second()
            logView.text = BotLog.text()
            h.postDelayed(this, 1000)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Store.init(this); BotLog.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 28, 28, 28) }

        fun field(key: String, label: String, def: String, multi: Boolean = false, num: Boolean = false) {
            root.addView(TextView(this).apply { text = label; setPadding(0, 16, 0, 0) })
            val e = EditText(this).apply {
                setText(Store.s(key, def))
                if (num) inputType = InputType.TYPE_CLASS_NUMBER
                else if (multi) { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 3 }
            }
            fields[key] = e; root.addView(e)
        }
        fun btn(label: String, onClick: () -> Unit) {
            root.addView(Button(this).apply { text = label; setOnClickListener { onClick() } })
        }

        stats = TextView(this); root.addView(stats)
        btn("НАСТРОЙКА REDMI: мастер (сделай один раз)") { startActivity(Intent(this, SetupActivity::class.java)) }
        btn("Аккаунты (карточки)") { startActivity(Intent(this, AccountsActivity::class.java)) }
        btn("Расписание, ночной режим, автопауза") { startActivity(Intent(this, ScheduleActivity::class.java)) }
        btn("Статистика по дням") { startActivity(Intent(this, StatsActivity::class.java)) }
        btn("Белый список (кого бот не отписывает)") { showWhitelist() }
        btn("Скопировать журнал") { copyLog() }
        btn("Экспорт настроек в файл") { exportSettings() }
        btn("Импорт настроек из файла") { importSettings() }

        field("acc", "Ник ТЕКУЩЕГО аккаунта (для кнопок ниже без переключения)", "acc1")
        field("donors", "Аккаунты-доноры (по одному в строке) — с их подписчиков бот будет подписываться", "", multi = true)
        field("special", "ОСОБЫЙ аккаунт (ник одного из твоих аккаунтов): он берёт доноров только из поля ниже, а не из общего списка", "")
        field("donorsSpecial", "Доноры особого аккаунта (сверху вниз). Когда подписчики донора закончились, бот сам переходит к следующему. Хочешь другого донора — впиши его ник сюда", "", multi = true)
        field("followDay", "Подписок в день (база для уровня «Обычный»)", "20", num = true)
        field("unfollowWeek", "Отписок раз в неделю (база)", "50", num = true)
        field("likes", "Лайков за сессию (база)", "10", num = true)
        field("likesPerProfile", "Лайков на один профиль", "2", num = true)
        field("storiesPerProfile", "Историй смотреть на профиль", "5", num = true)
        field("commentPct", "Шанс комментария после лайка, %", "20", num = true)
        field("maxMin", "Лимит времени сессии активности, мин", "30", num = true)
        field("followHour", "Подписок в час, максимум (база)", "12", num = true)
        field("unfollowHour", "Отписок в час, максимум (база)", "15", num = true)
        field("diversityPct", "Шанс «прогулки» по ленте / рилсам / чужим историям между шагами, %", "60", num = true)
        field("skipDayPct", "Шанс выходного для аккаунта в режиме расписания, % (два дня подряд не пропускает)", "10", num = true)
        field("comments", "Комментарии (по одному в строке, бот выбирает случайный)",
            "Красиво!\nУдивительно!\nВау, очень круто\nОтличная работа\nОчень стильно\nПотрясающе!", multi = true)

        btn("Уровни и лимиты аккаунтов (новый / обычный / прогретый)") { showTiers() }

        btn("Подписки (до дневного лимита)") { run("follow") }
        btn("Активность: истории + лайки + комменты") { run("engage") }
        btn("Отписка (самых старых)") { run("unfollow") }
        btn("АВТО: подписки → активность → отписка раз в неделю") { run("auto") }
        btn("ВСЕ АККАУНТЫ: по очереди, авто-режим на каждом") { run("all") }
        btn("РАСПИСАНИЕ: аккаунты сами стартуют в разное время дня") { run("sched") }
        btn("Проверить аккаунты (найдёт ники в Instagram)") { run("check") }
        btn("Разнообразие: проверить прямо сейчас (Instagram должен быть открыт)") { run("browse") }
        btn("Паузы после блокировок (посмотреть / снять)") { showPauses() }
        btn("Особый аккаунт: сбросить «пройденных» доноров") {
            save()
            val sp = Store.special()
            if (sp.isEmpty()) Toast.makeText(this, "Сначала впиши ник особого аккаунта", Toast.LENGTH_LONG).show()
            else { Store.resetDonorsDone(sp); Toast.makeText(this, "Список пройденных доноров @$sp очищен", Toast.LENGTH_LONG).show() }
        }
        btn("СТОП") { BotService.instance?.stop() }

        // ── диагностика ──
        root.addView(TextView(this).apply { text = "ДИАГНОСТИКА"; textSize = 16f; setPadding(0, 32, 0, 0) })
        btn("Последняя ошибка (вылет)") {
            val t = CrashHandler.last(this)
            showText("Последняя ошибка", if (t.isEmpty()) "Вылетов и сбоев пока не было." else t)
        }
        btn("Снимок экрана Instagram (через 10 сек)") {
            val s = BotService.instance
            if (s == null) Toast.makeText(this, "Сначала включи спецвозможности", Toast.LENGTH_LONG).show()
            else {
                s.dumpScreen()
                Toast.makeText(this, "Открой Instagram и нужный экран. Снимок через 10 сек", Toast.LENGTH_LONG).show()
                moveTaskToBack(true)
            }
        }
        btn("Показать снимок экрана") {
            val f = File(filesDir, BotService.DUMP_FILE)
            showText("Снимок экрана", if (f.exists()) f.readText().take(120_000) else "Снимка ещё нет.")
        }
        btn("Журнал целиком (копировать / отправить)") { showText("Журнал", BotLog.text()) }
        btn("Очистить журнал") { BotLog.clear() }

        logView = TextView(this).apply { textSize = 12f; setPadding(0, 24, 0, 0) }
        root.addView(logView)
        setContentView(ScrollView(this).apply { addView(root) })

        // при самом первом запуске сразу открываем мастер настройки
        if (Store.s("setupShown") != "1") {
            Store.put("setupShown", "1")
            startActivity(Intent(this, SetupActivity::class.java))
        }

        if (CrashHandler.last(this).isNotEmpty())
            Toast.makeText(this, "Был сбой — смотри «Последняя ошибка» внизу", Toast.LENGTH_LONG).show()
    }

    private val REQ_EXPORT = 501
    private val REQ_IMPORT = 502

    private fun copyLog() {
        val t = BotLog.text()
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("HuntaBot log", t))
        Toast.makeText(this, if (t.isEmpty()) "Журнал пуст" else "Журнал скопирован (${t.lines().size} строк)", Toast.LENGTH_SHORT).show()
    }

    private fun exportSettings() {
        save()
        try {
            startActivityForResult(
                Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/json").putExtra(Intent.EXTRA_TITLE, Backup.fileName()),
                REQ_EXPORT
            )
        } catch (e: Throwable) { Toast.makeText(this, "Не открылся выбор файла: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    private fun importSettings() {
        AlertDialog.Builder(this).setTitle("Импорт настроек")
            .setMessage("Настройки и данные аккаунтов из файла заменят текущие совпадающие значения. Продолжить?")
            .setPositiveButton("Выбрать файл") { _, _ ->
                try {
                    startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
                        REQ_IMPORT
                    )
                } catch (e: Throwable) { Toast.makeText(this, "Не открылся выбор файла: ${e.message}", Toast.LENGTH_LONG).show() }
            }
            .setNegativeButton("Отмена", null).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri: Uri = data?.data ?: return
        if (res != RESULT_OK) return
        try {
            if (req == REQ_EXPORT) {
                contentResolver.openOutputStream(uri)!!.use { it.write(Backup.export().toByteArray(Charsets.UTF_8)) }
                Toast.makeText(this, "Настройки сохранены в файл", Toast.LENGTH_LONG).show()
            } else if (req == REQ_IMPORT) {
                val text = contentResolver.openInputStream(uri)!!.use { String(it.readBytes(), Charsets.UTF_8) }
                val n = Backup.import(text)
                BotLog.add("Импорт настроек: восстановлено значений: $n")
                Toast.makeText(this, "Импортировано значений: $n", Toast.LENGTH_LONG).show()
                noSave = true   // иначе onPause затрёт импортированное старыми значениями полей
                recreate()
            }
        } catch (e: Throwable) {
            Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showWhitelist() {
        val e = EditText(this).apply {
            setText(Store.s("whitelist"))
            hint = "ники по одному в строке"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 5
        }
        AlertDialog.Builder(this).setTitle("Белый список")
            .setMessage("Этих ников бот никогда не отписывает.")
            .setView(e)
            .setPositiveButton("Сохранить") { _, _ ->
                Store.put("whitelist", e.text.toString())
                Toast.makeText(this, "В белом списке: ${Store.whitelist().size}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null).show()
    }

    /** Какие аккаунты сейчас на паузе после блокировки Instagram. */
    private fun showPauses() {
        val fmt = SimpleDateFormat("dd.MM HH:mm", Locale.US)
        val now = System.currentTimeMillis()
        val accs = Store.accounts().ifEmpty { listOf(Store.acc()) }
        val text = accs.joinToString("\n") { a ->
            val p = Store.pausedUntil(a)
            val st = if (p > now) "пауза до ${fmt.format(Date(p))}" else "работает"
            "@$a — $st (блокировок за всё время: ${Store.blocks(a).size})"
        }
        AlertDialog.Builder(this).setTitle("Паузы после блокировок")
            .setMessage(text)
            .setPositiveButton("Снять все паузы") { _, _ ->
                accs.forEach { Store.clearPause(it) }
                Toast.makeText(this, "Паузы сняты", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    /** Окно выбора уровня для каждого аккаунта: лимиты считаются отдельно и растут по неделям у «Нового». */
    private fun showTiers() {
        save()
        val accs = Store.accounts().ifEmpty { listOf(Store.acc()) }
        val names = arrayOf("Новый (лимиты растут 4 недели)", "Обычный (100% базы)", "Прогретый (150% базы)")
        val keys = arrayOf(Limits.NEW, Limits.NORMAL, Limits.WARM)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 16, 28, 16) }
        val spinners = LinkedHashMap<String, Spinner>()
        for (a in accs) {
            col.addView(TextView(this).apply { text = "@$a\n${Limits.describe(a)}"; setPadding(0, 24, 0, 0) })
            val sp = Spinner(this).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, names)
                setSelection(keys.indexOf(Limits.tier(a)).coerceAtLeast(0))
            }
            col.addView(sp); spinners[a] = sp
        }
        AlertDialog.Builder(this).setTitle("Уровни аккаунтов")
            .setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("Сохранить") { _, _ ->
                for ((a, sp) in spinners) {
                    val t = keys[sp.selectedItemPosition]
                    if (t != Limits.tier(a)) {
                        Limits.setTier(a, t)
                        if (t == Limits.NEW) Limits.resetSince(a)
                    }
                }
                Toast.makeText(this, "Уровни сохранены", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Сбросить рост") { _, _ ->
                for (a in spinners.keys) Limits.resetSince(a)
                Toast.makeText(this, "Отсчёт недель начат заново", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showText(title: String, text: String) {
        val body = text.ifEmpty { "(пусто)" }
        val tv = TextView(this).apply {
            this.text = body; textSize = 11f; typeface = Typeface.MONOSPACE
            setPadding(28, 16, 28, 16); setTextIsSelectable(true)
        }
        AlertDialog.Builder(this).setTitle(title)
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Копировать") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Leongram", body))
                Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Отправить") { _, _ ->
                val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body.takeLast(100_000))
                startActivity(Intent.createChooser(i, "Отправить"))
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun save() = fields.forEach { (k, e) -> Store.put(k, e.text.toString()) }

    private fun run(mode: String) {
        save()
        val s = BotService.instance
        if (s == null) Toast.makeText(this, "Сначала включи спецвозможности HuntaBot", Toast.LENGTH_LONG).show()
        else { s.start(mode); moveTaskToBack(true) }
    }

    override fun onResume() { super.onResume(); h.post(tick) }
    override fun onPause() { super.onPause(); if (!noSave) save(); h.removeCallbacks(tick) }
}
