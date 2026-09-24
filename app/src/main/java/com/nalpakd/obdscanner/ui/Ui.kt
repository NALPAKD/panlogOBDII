package com.nalpakd.obdscanner.ui

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator

/** Small helpers so every screen can be built in code (no XML layouts to keep in sync). */
object Ui {
    fun dp(ctx: Context, v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    fun column(ctx: Context, padDp: Int = 0): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(ctx, padDp)
        setPadding(p, p, p, p)
    }

    fun row(ctx: Context): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

    /** Sets a scrolling column as the activity content and returns the column. */
    fun scrollScreen(a: Activity): LinearLayout {
        val sv = ScrollView(a)
        val col = column(a, 16)
        sv.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        a.setContentView(sv)
        return col
    }

    fun matchWidth(ctx: Context, topMarginDp: Int = 8) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(ctx, topMarginDp) }

    fun weighted(ctx: Context, marginDp: Int = 4) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        val m = dp(ctx, marginDp); leftMargin = m; rightMargin = m
    }

    fun text(ctx: Context, s: String, sizeSp: Float = 15f, bold: Boolean = false): TextView = TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setTextIsSelectable(false)
    }

    fun title(ctx: Context, s: String) = text(ctx, s, 20f, true)

    fun mono(ctx: Context, s: String): TextView = text(ctx, s, 12.5f).apply {
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
    }

    fun button(ctx: Context, label: String, outlined: Boolean = false, onClick: (View) -> Unit): MaterialButton {
        val b = if (outlined) MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        else MaterialButton(ctx)
        b.text = label
        b.isAllCaps = false
        b.setOnClickListener(onClick)
        return b
    }

    fun card(ctx: Context): Pair<MaterialCardView, LinearLayout> {
        val card = MaterialCardView(ctx)
        card.radius = dp(ctx, 14).toFloat()
        card.cardElevation = dp(ctx, 2).toFloat()
        val inner = column(ctx, 14)
        card.addView(inner)
        return card to inner
    }

    class Progress(val dialog: AlertDialog, val label: TextView, val bar: LinearProgressIndicator) {
        fun update(msg: String, pct: Int) {
            label.text = msg
            if (pct in 0..100) { bar.isIndeterminate = false; bar.setProgressCompat(pct, true) }
        }
        fun dismiss() = try { dialog.dismiss() } catch (_: Exception) { }
    }

    fun progress(a: Activity, title: String, onCancel: (() -> Unit)?): Progress {
        val col = column(a, 20)
        val label = text(a, "Starting…")
        val bar = LinearProgressIndicator(a).apply { isIndeterminate = true }
        col.addView(label)
        col.addView(bar, matchWidth(a, 14))
        val b = MaterialAlertDialogBuilder(a).setTitle(title).setView(col).setCancelable(false)
        if (onCancel != null) b.setNegativeButton("Cancel") { _, _ -> onCancel() }
        return Progress(b.show(), label, bar)
    }

    fun alert(a: Activity, title: String, msg: String) {
        MaterialAlertDialogBuilder(a).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()
    }
}
