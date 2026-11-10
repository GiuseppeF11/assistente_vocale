package it.assistente.vocale

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

/*
 * Piccolo kit grafico per le schermate di impostazione, costruite da codice:
 * titoli di gruppo, schede arrotondate, righe con titolo e descrizione, interruttori, menu, cursori.
 * Tutte le schermate usano gli stessi elementi, così l'aspetto resta coerente.
 */

fun Activity.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

/** Apre una schermata di sistema; se il telefono non la ha, apre le Impostazioni. */
fun Activity.openSafe(intent: Intent) {
    try { startActivity(intent) } catch (e: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
}

/** Spinner che chiama [onPick] solo quando l'utente cambia davvero la scelta. */
fun Spinner.bind(items: List<String>, selected: Int, onPick: (Int) -> Unit) {
    val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, items)
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    onItemSelectedListener = null
    this.adapter = adapter
    setSelection(selected.coerceIn(0, (items.size - 1).coerceAtLeast(0)), false)
    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = onPick(position)
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }
}

/** Una riga: titolo e descrizione (aggiornabili). */
class Row(val view: View, val title: TextView, val summary: TextView) {
    fun set(summaryText: CharSequence?) {
        summary.text = summaryText ?: ""
        summary.visibility = if (summaryText.isNullOrEmpty()) View.GONE else View.VISIBLE
    }
    var visible: Boolean
        get() = view.visibility == View.VISIBLE
        set(v) { view.visibility = if (v) View.VISIBLE else View.GONE }
}

class Form(val a: Activity, val root: LinearLayout) {

    private fun color(id: Int) = a.getColor(id)

    private fun text(size: Float, colorId: Int, bold: Boolean = false) = TextView(a).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color(colorId))
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(w, h)

    /** Titolo di gruppo, piccolo e maiuscolo, sopra una scheda. */
    fun header(title: String) {
        root.addView(text(13f, R.color.accent_text, bold = true).apply {
            text = title.uppercase()
            letterSpacing = 0.08f
            setPadding(a.dp(8), a.dp(24), a.dp(8), a.dp(8))
        })
    }

    /** Scheda arrotondata che contiene righe separate da sottili linee. */
    fun card(): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(color(R.color.surface))
            cornerRadius = a.dp(18).toFloat()
        }
        clipToOutline = true
        root.addView(this, lp())
    }

    private fun LinearLayout.addRow(v: View) {
        if (childCount > 0) addView(View(a).apply { setBackgroundColor(color(R.color.divider)) },
            lp(h = 1).apply { marginStart = a.dp(16) })
        addView(v)
    }

    private fun rowContainer(clickable: Boolean): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = a.dp(56)
        setPadding(a.dp(16), a.dp(12), a.dp(16), a.dp(12))
        if (clickable) {
            val tv = TypedValue()
            a.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
        }
    }

    private fun texts(title: String, summary: String?): Triple<LinearLayout, TextView, TextView> {
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val t = text(16f, R.color.text).apply { this.text = title }
        val s = text(14f, R.color.text_dim).apply {
            this.text = summary ?: ""
            visibility = if (summary.isNullOrEmpty()) View.GONE else View.VISIBLE
            setPadding(0, a.dp(2), 0, 0)
            setLineSpacing(a.dp(2).toFloat(), 1f)
        }
        col.addView(t); col.addView(s)
        return Triple(col, t, s)
    }

    /** Riga con icona, titolo, descrizione e freccia (se cliccabile). */
    fun row(card: LinearLayout, title: String, summary: String? = null, icon: String? = null, onClick: (() -> Unit)? = null): Row {
        val r = rowContainer(onClick != null)
        if (icon != null) {
            r.addView(text(20f, R.color.text).apply {
                text = icon
                gravity = Gravity.CENTER
            }, lp(a.dp(40), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = a.dp(12) })
        }
        val (col, t, s) = texts(title, summary)
        r.addView(col, lp(0).apply { weight = 1f })
        if (onClick != null) {
            r.addView(text(22f, R.color.text_dim).apply { text = "›"; setPadding(a.dp(12), 0, 0, 0) })
            r.setOnClickListener { onClick() }
        }
        card.addRow(r)
        return Row(r, t, s)
    }

    /** Riga con interruttore a destra (tutta la riga è toccabile). */
    fun switchRow(card: LinearLayout, title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit): Pair<Row, Switch> {
        val r = rowContainer(true)
        val (col, t, s) = texts(title, summary)
        r.addView(col, lp(0).apply { weight = 1f })
        val sw = Switch(a).apply { isChecked = checked; setPadding(a.dp(12), 0, 0, 0) }
        sw.setOnCheckedChangeListener { _, on -> onChange(on) }
        r.addView(sw)
        r.setOnClickListener { sw.toggle() }
        card.addRow(r)
        return Row(r, t, s) to sw
    }

    /** Riga con titolo e menu a tendina sotto. */
    fun spinnerRow(card: LinearLayout, title: String, items: List<String>, selected: Int, summary: String? = null, onPick: (Int) -> Unit): Pair<Row, Spinner> {
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(16), a.dp(12), a.dp(16), a.dp(8))
        }
        val (texts, t, s) = texts(title, summary)
        col.addView(texts)
        val sp = Spinner(a).apply { bind(items, selected, onPick) }
        col.addView(sp, lp(h = a.dp(48)))
        card.addRow(col)
        return Row(col, t, s) to sp
    }

    /** Riga con cursore: [label] calcola il testo mostrato per il valore. */
    fun sliderRow(card: LinearLayout, max: Int, value: Int, label: (Int) -> String, onStop: (Int) -> Unit): Pair<Row, SeekBar> {
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(16), a.dp(12), a.dp(16), a.dp(8))
        }
        val (texts, t, s) = texts(label(value), null)
        col.addView(texts)
        val sb = SeekBar(a).apply {
            this.max = max
            progress = value
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar?, p: Int, fromUser: Boolean) { t.text = label(p) }
                override fun onStartTrackingTouch(b: SeekBar?) {}
                override fun onStopTrackingTouch(b: SeekBar?) = onStop(progress)
            })
        }
        col.addView(sb, lp(h = a.dp(40)))
        card.addRow(col)
        return Row(col, t, s) to sb
    }

    /** Scelta tra opzioni (radio), ciascuna con descrizione. */
    fun radioRows(card: LinearLayout, options: List<Pair<String, String>>, selected: Int, onPick: (Int) -> Unit): RadioGroup {
        val group = RadioGroup(a).apply { setPadding(a.dp(8), a.dp(4), a.dp(16), a.dp(4)) }
        options.forEachIndexed { i, (title, sub) ->
            group.addView(RadioButton(a).apply {
                id = View.generateViewId()
                text = android.text.SpannableStringBuilder(title).append("\n").append(
                    android.text.SpannableString(sub).apply {
                        setSpan(android.text.style.ForegroundColorSpan(color(R.color.text_dim)), 0, sub.length, 0)
                        setSpan(android.text.style.RelativeSizeSpan(0.85f), 0, sub.length, 0)
                    }
                )
                setTextColor(color(R.color.text))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                minimumHeight = a.dp(56)
                setPadding(a.dp(8), a.dp(8), 0, a.dp(8))
                isChecked = i == selected
            })
        }
        group.setOnCheckedChangeListener { g, id ->
            val i = (0 until g.childCount).indexOfFirst { g.getChildAt(it).id == id }
            if (i >= 0) onPick(i)
        }
        card.addRow(group)
        return group
    }

    /** Campo di testo con eventuale pulsante a destra. */
    fun editRow(card: LinearLayout, title: String, value: String, hint: String, button: String? = null, onButton: ((String) -> Unit)? = null): Pair<Row, EditText> {
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(16), a.dp(12), a.dp(16), a.dp(8))
        }
        val (texts, t, s) = texts(title, null)
        col.addView(texts)
        val line = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val et = EditText(a).apply {
            setText(value)
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setTextColor(color(R.color.text))
            setHintTextColor(color(R.color.text_dim))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        line.addView(et, lp(0).apply { weight = 1f })
        if (button != null && onButton != null) {
            line.addView(Button(a).apply {
                text = button
                isAllCaps = false
                setOnClickListener { onButton(et.text.toString()) }
            }, lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = a.dp(8) })
        }
        col.addView(line)
        card.addRow(col)
        return Row(col, t, s) to et
    }

    /** Pulsante dentro una scheda; [primary] = colorato. */
    fun button(card: LinearLayout, label: String, primary: Boolean = false, onClick: () -> Unit): Button {
        val b = Button(a).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            if (primary) {
                background = GradientDrawable().apply {
                    setColor(color(R.color.listening)); cornerRadius = a.dp(12).toFloat()
                }
                setTextColor(color(R.color.text))
            }
            setOnClickListener { onClick() }
        }
        val wrap = LinearLayout(a).apply { setPadding(a.dp(12), a.dp(4), a.dp(12), a.dp(8)) }
        wrap.addView(b, lp())
        card.addView(wrap)
        return b
    }

    /** Nota esplicativa breve, fuori o dentro una scheda. */
    fun note(text: String, card: LinearLayout? = null): TextView {
        val tv = text(13f, R.color.text_dim).apply {
            this.text = text
            setLineSpacing(a.dp(2).toFloat(), 1f)
            setPadding(a.dp(if (card == null) 8 else 16), a.dp(8), a.dp(if (card == null) 8 else 16), a.dp(if (card == null) 0 else 12))
        }
        (card ?: root).addView(tv)
        return tv
    }
}
