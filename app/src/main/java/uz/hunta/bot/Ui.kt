package uz.hunta.bot

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*

/** Небольшой набор помощников для экранов без XML-разметки. */
object Ui {
    val BG = 0xFFF2F3F5.toInt()
    val CARD = 0xFFFFFFFF.toInt()
    val TEXT = 0xFF1B1F23.toInt()
    val MUTED = 0xFF6B7280.toInt()
    val GREEN = 0xFF1E9E5A.toInt()
    val RED = 0xFFD64545.toInt()
    val BLUE = 0xFF2F6FED.toInt()
    val GRAY = 0xFFB8BDC6.toInt()
    val DARK = 0xFF39404D.toInt()

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density + 0.5f).toInt()

    fun bg(c: Context, color: Int, radius: Int = 12): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(c, radius).toFloat() }

    fun lp(c: Context, top: Int = 0, bottom: Int = 0, weight: Float = 0f): LinearLayout.LayoutParams =
        if (weight > 0f) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply {
            topMargin = dp(c, top); bottomMargin = dp(c, bottom)
        } else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(c, top); bottomMargin = dp(c, bottom)
        }

    fun text(c: Context, t: String, size: Float = 14f, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(c).apply {
            text = t; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    fun card(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = bg(c, CARD, 14)
        val p = dp(c, 14); setPadding(p, p, p, p)
    }

    fun row(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
    }

    /** kind: 0 — обычная, 1 — главная (синяя), 2 — опасная (красная), 3 — зелёная. */
    fun button(c: Context, label: String, kind: Int = 0, onClick: () -> Unit): Button = Button(c).apply {
        text = label; isAllCaps = false; textSize = 14f
        val fill = when (kind) { 1 -> BLUE; 2 -> RED; 3 -> GREEN; else -> 0xFFE4E7EC.toInt() }
        background = bg(c, fill, 10)
        setTextColor(if (kind == 0) TEXT else 0xFFFFFFFF.toInt())
        minHeight = dp(c, 44); minimumHeight = dp(c, 44)
        setOnClickListener { onClick() }
    }

    fun chip(c: Context, t: String, color: Int): TextView = TextView(c).apply {
        text = t; textSize = 12f; setTextColor(0xFFFFFFFF.toInt())
        background = bg(c, color, 20)
        setPadding(dp(c, 10), dp(c, 3), dp(c, 10), dp(c, 3))
    }

    fun spinner(c: Context, items: List<String>, sel: Int, onPick: (Int) -> Unit): Spinner = Spinner(c).apply {
        adapter = ArrayAdapter(c, android.R.layout.simple_spinner_dropdown_item, items)
        setSelection(sel.coerceIn(0, items.size - 1), false)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { onPick(pos) }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }
}

/** Общая основа экранов: серый фон, прокрутка, вертикальная колонка [col]. */
abstract class Page : Activity() {
    protected lateinit var col: LinearLayout

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Store.init(this); BotLog.init(this)
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(this@Page, 16); setPadding(p, p, p, p * 2)
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(col) })
    }

    override fun onResume() { super.onResume(); render() }

    protected abstract fun render()

    protected fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}
