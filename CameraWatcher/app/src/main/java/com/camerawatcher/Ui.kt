package com.camerawatcher

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView

object Theme {
    val BG = Color.parseColor("#0F1115")
    val CARD = Color.parseColor("#181B22")
    val CARD2 = Color.parseColor("#222632")
    val ACCENT = Color.parseColor("#3DDC97")
    val DANGER = Color.parseColor("#FF5C5C")
    val TEXT = Color.parseColor("#E8EAF0")
    val MUTED = Color.parseColor("#8B93A7")
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun roundBg(color: Int, radiusDp: Int, ctx: Context): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = ctx.dp(radiusDp).toFloat()
    }

fun Context.text(s: String, sizeSp: Float = 15f, color: Int = Theme.TEXT, bold: Boolean = false): TextView =
    TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

fun Context.card(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = roundBg(Theme.CARD, 16, this@card)
    setPadding(dp(16), dp(14), dp(16), dp(14))
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(12) }
}

fun Context.button(label: String, color: Int = Theme.ACCENT, textColor: Int = Color.parseColor("#06210F"), onClick: () -> Unit): TextView =
    TextView(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(textColor)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = roundBg(color, 14, this@button)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) }
    }

/** Строка «название — значение», по нажатию вызывает onClick. */
fun Context.row(title: String, value: String, onClick: (() -> Unit)?): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, dp(10))
        val t = text(title, 15f)
        t.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        addView(t)
        addView(text(value, 15f, Theme.ACCENT, true))
        if (onClick != null) setOnClickListener { onClick() }
    }

fun Context.pickList(title: String, items: List<String>, checked: Int, onPick: (Int) -> Unit) {
    AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
        .setTitle(title)
        .setSingleChoiceItems(items.toTypedArray(), checked) { d, which ->
            d.dismiss()
            onPick(which)
        }
        .setNegativeButton(getString(R.string.cancel), null)
        .show()
}

fun Context.pickNumber(title: String, min: Int, max: Int, current: Int, onPick: (Int) -> Unit) {
    val np = NumberPicker(this).apply {
        minValue = min
        maxValue = max
        value = current.coerceIn(min, max)
        wrapSelectorWheel = false
    }
    val box = FrameLayout(this).apply {
        setPadding(0, dp(12), 0, dp(12))
        addView(np, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }
    AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
        .setTitle(title)
        .setView(box)
        .setPositiveButton("OK") { _, _ -> onPick(np.value) }
        .setNegativeButton(getString(R.string.cancel), null)
        .show()
}

fun Context.editText(title: String, current: String, multiline: Boolean = false, onOk: (String) -> Unit) {
    val et = EditText(this).apply {
        setText(current)
        setTextColor(Theme.TEXT)
        inputType = if (multiline) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE else InputType.TYPE_CLASS_TEXT
        setSingleLine(!multiline)
    }
    val box = FrameLayout(this).apply {
        setPadding(dp(20), dp(8), dp(20), 0)
        addView(et)
    }
    AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
        .setTitle(title)
        .setView(box)
        .setPositiveButton("OK") { _, _ -> onOk(et.text.toString()) }
        .setNegativeButton(getString(R.string.cancel), null)
        .show()
}

fun Context.info(title: String, msg: String, onOk: (() -> Unit)? = null) {
    AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
        .setTitle(title)
        .setMessage(msg)
        .setPositiveButton(getString(R.string.got_it)) { _, _ -> onOk?.invoke() }
        .show()
}

/** Контейнер, сохраняющий пропорции кадра (и не выше maxHeightPx). */
class AspectFrame(ctx: Context) : FrameLayout(ctx) {
    var ratio = 16f / 9f
        set(v) {
            field = v
            requestLayout()
        }
    var maxHeightPx = Int.MAX_VALUE

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val avail = MeasureSpec.getSize(widthSpec)
        var w = avail
        var h = (w / ratio).toInt()
        if (h > maxHeightPx) {
            h = maxHeightPx
            w = (h * ratio).toInt()
        }
        val ws = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        val hs = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(ws, hs)
        setMeasuredDimension(w, h)
    }
}

/** Сетка поверх превью: показывает клетки с движением, маску исключённых зон, позволяет закрашивать маску пальцем. */
class GridOverlayView(ctx: Context) : View(ctx) {
    var cols = 0
    var rows = 0
    var mask = BooleanArray(0)
    var active = BooleanArray(0)
    var recording = false
    var editMode = false
    var onMaskChanged: ((BooleanArray) -> Unit)? = null

    private val line = Paint().apply { color = Color.argb(70, 255, 255, 255); strokeWidth = 1f }
    private val fillMask = Paint().apply { color = Color.argb(120, 255, 80, 80) }
    private val fillAct = Paint().apply { color = Color.argb(110, 61, 220, 151) }
    private val border = Paint().apply {
        color = Theme.DANGER
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private var paintValue = true

    fun setGrid(c: Int, r: Int, m: BooleanArray) {
        if (c != cols || r != rows || m.size != mask.size) {
            cols = c
            rows = r
            active = BooleanArray(c * r)
        }
        mask = m
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (cols == 0 || rows == 0) return
        val cw = width.toFloat() / cols
        val ch = height.toFloat() / rows
        for (i in mask.indices) {
            val x = (i % cols) * cw
            val y = (i / cols) * ch
            if (mask[i]) canvas.drawRect(x, y, x + cw, y + ch, fillMask)
            else if (i < active.size && active[i]) canvas.drawRect(x, y, x + cw, y + ch, fillAct)
        }
        for (c in 1 until cols) canvas.drawLine(c * cw, 0f, c * cw, height.toFloat(), line)
        for (r in 1 until rows) canvas.drawLine(0f, r * ch, width.toFloat(), r * ch, line)
        if (recording) canvas.drawRect(3f, 3f, width - 3f, height - 3f, border)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!editMode || cols == 0) return false
        val cx = (e.x / width * cols).toInt().coerceIn(0, cols - 1)
        val cy = (e.y / height * rows).toInt().coerceIn(0, rows - 1)
        val idx = cy * cols + cx
        if (idx !in mask.indices) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                paintValue = !mask[idx]
                mask[idx] = paintValue
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (mask[idx] != paintValue) {
                    mask[idx] = paintValue
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onMaskChanged?.invoke(mask)
        }
        return true
    }
}
