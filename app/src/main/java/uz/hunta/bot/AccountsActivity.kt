package uz.hunta.bot

import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.Switch

/** Список аккаунтов карточками: добавить, удалить, включить/выключить, статус и счётчики. */
class AccountsActivity : Page() {
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { render(); h.postDelayed(this, 3000) }
    }

    override fun onResume() { super.onResume(); h.removeCallbacks(tick); h.postDelayed(tick, 3000) }
    override fun onPause() { super.onPause(); h.removeCallbacks(tick) }

    override fun render() {
        col.removeAllViews()
        val all = Store.accounts()
        val active = Store.activeAccounts()
        col.addView(Ui.text(this, "Аккаунты", 24f, bold = true))
        col.addView(
            Ui.text(this, "Всего ${all.size}, включено ${active.size}. Режимы «ВСЕ АККАУНТЫ» и «РАСПИСАНИЕ» работают только с включёнными.", 13f, Ui.MUTED),
            Ui.lp(this, top = 4, bottom = 10)
        )
        col.addView(Ui.button(this, "+ Добавить аккаунт", 1) { addDialog() })
        if (all.isEmpty()) {
            col.addView(
                Ui.text(this, "Пока пусто. Нажми «Добавить» и впиши ник так, как он показан в Instagram (без @). Можно вставить сразу несколько.", 14f, Ui.MUTED),
                Ui.lp(this, top = 16)
            )
        }
        for (a in all) col.addView(cardFor(a), Ui.lp(this, top = 10))
    }

    private fun statusOf(a: String): Pair<String, Int> {
        val now = System.currentTimeMillis()
        val paused = Store.pausedUntil(a)
        return when {
            !Store.enabled(a) -> "Выключен" to Ui.GRAY
            paused > now -> "⛔ Пауза до ${Schedule.dm(paused)}" to Ui.RED
            BotService.running && (Store.cur == a || (Store.cur == null && Store.acc() == a)) -> "▶ Работает" to Ui.GREEN
            else -> "Готов" to Ui.BLUE
        }
    }

    private fun cardFor(a: String): android.view.View {
        val c = Ui.card(this)
        val head = Ui.row(this)
        head.addView(Ui.text(this, "@$a", 18f, bold = true), Ui.lp(this, weight = 1f))
        head.addView(Switch(this).apply {
            isChecked = Store.enabled(a)
            setOnCheckedChangeListener { _, on -> Store.setEnabled(a, on); render() }
        })
        c.addView(head)

        val (label, color) = statusOf(a)
        val chipRow = Ui.row(this)
        chipRow.addView(Ui.chip(this, label, color))
        c.addView(chipRow, Ui.lp(this, top = 6))

        val t = Stats.forDay(a, 0)
        c.addView(
            Ui.text(this, "Сегодня: подписок ${t[Stats.FOLLOW]} · лайков ${t[Stats.LIKE]} · отписок ${t[Stats.UNFOLLOW]} · комментариев ${t[Stats.COMMENT]}", 14f),
            Ui.lp(this, top = 10)
        )
        c.addView(
            Ui.text(this, "В списке бота (подписан): ${Store.followedCount(a)} · блокировок за всё время: ${Store.blocks(a).size}", 13f, Ui.MUTED),
            Ui.lp(this, top = 2)
        )
        c.addView(Ui.text(this, Limits.describe(a), 12f, Ui.MUTED), Ui.lp(this, top = 6))

        val btns = Ui.row(this)
        btns.addView(Ui.button(this, "Уровень") { tierDialog(a) }, Ui.lp(this, weight = 1f))
        if (Store.pausedUntil(a) > System.currentTimeMillis()) {
            btns.addView(Ui.button(this, "Снять паузу") { Store.clearPause(a); render() }, Ui.lp(this, weight = 1f))
        }
        btns.addView(Ui.button(this, "Удалить", 2) { deleteDialog(a) }, Ui.lp(this, weight = 1f))
        c.addView(btns, Ui.lp(this, top = 10))
        return c
    }

    private fun addDialog() {
        val e = EditText(this).apply {
            hint = "ник, без @ (можно несколько — через пробел или с новой строки)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 2
        }
        AlertDialog.Builder(this).setTitle("Добавить аккаунт")
            .setView(e)
            .setPositiveButton("Добавить") { _, _ ->
                val nicks = e.text.toString().split(Regex("[\\s,;]+")).filter { it.isNotBlank() }
                val ok = nicks.count { Store.addAccount(it) }
                toast(
                    when {
                        nicks.isEmpty() -> "Ничего не введено"
                        ok == 0 -> "Не добавлено: ник неверный или уже есть в списке"
                        else -> "Добавлено: $ok"
                    }
                )
                render()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun tierDialog(a: String) {
        val names = arrayOf("Новый — лимиты растут 4 недели", "Обычный — 100% базы", "Прогретый — 150% базы")
        val keys = arrayOf(Limits.NEW, Limits.NORMAL, Limits.WARM)
        AlertDialog.Builder(this).setTitle("Уровень @$a")
            .setSingleChoiceItems(names, keys.indexOf(Limits.tier(a)).coerceAtLeast(0)) { d, which ->
                val t = keys[which]
                if (t != Limits.tier(a)) {
                    Limits.setTier(a, t)
                    if (t == Limits.NEW) Limits.resetSince(a)
                }
                d.dismiss(); render()
            }
            .setNeutralButton("Сбросить рост") { _, _ -> Limits.resetSince(a); toast("Отсчёт недель начат заново"); render() }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun deleteDialog(a: String) {
        AlertDialog.Builder(this).setTitle("Удалить @$a?")
            .setMessage("Из приложения пропадут настройки, список «на кого подписан бот» и статистика этого аккаунта. Сам аккаунт в Instagram не меняется.")
            .setPositiveButton("Удалить") { _, _ -> Store.removeAccount(a); render() }
            .setNegativeButton("Отмена", null)
            .show()
    }
}
