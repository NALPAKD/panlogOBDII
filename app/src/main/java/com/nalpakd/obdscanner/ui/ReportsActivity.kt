package com.nalpakd.obdscanner.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nalpakd.obdscanner.data.ReportStore

/** Lists every saved scan; tap one to view, analyze, share or delete it. */
class ReportsActivity : AppCompatActivity() {

    private lateinit var store: ReportStore
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private var items: List<ReportStore.Entry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ReportStore(this)
        val root = Ui.column(this, 8)
        empty = Ui.text(this, "No saved scans yet. Connect and run a Full Scan.").apply {
            val p = Ui.dp(this@ReportsActivity, 16); setPadding(p, p, p, p)
        }
        list = ListView(this)
        root.addView(empty)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> options(items[pos]) }
    }

    override fun onResume() {
        super.onResume()
        items = store.list()
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val e = items[position]
            val col = Ui.column(this@ReportsActivity, 12)
            col.addView(Ui.text(this@ReportsActivity, "${e.date}  ·  ${e.vehicle}", 15f, true))
            val codes = if (e.stored + e.pending == 0) "No codes" else "${e.stored} stored, ${e.pending} pending"
            val mil = when (e.milOn) { true -> "CEL ON · "; false -> "CEL off · "; else -> "" }
            col.addView(Ui.text(this@ReportsActivity, "$mil$codes${if (e.hasReport) "  ·  ✓ AI report" else ""}", 13f))
            if (e.vin.isNotBlank()) col.addView(Ui.text(this@ReportsActivity, "VIN ${e.vin}", 12f))
            return col
        }
    }

    private fun options(e: ReportStore.Entry) {
        val labels = mutableListOf<String>()
        val acts = mutableListOf<() -> Unit>()
        if (e.hasReport) {
            labels.add("View AI report"); acts.add {
                startActivity(Intent(this, ReportViewActivity::class.java).putExtra(ScanResultActivity.EXTRA_STAMP, e.stamp))
            }
        }
        labels.add(if (e.hasReport) "Open scan / re-analyze" else "Open scan / analyze with Claude"); acts.add {
            startActivity(Intent(this, ScanResultActivity::class.java).putExtra(ScanResultActivity.EXTRA_STAMP, e.stamp))
        }
        labels.add("Share files"); acts.add {
            val files = listOf(store.reportHtml(e.stamp), store.scanTxt(e.stamp), store.scanJson(e.stamp))
            startActivity(store.shareIntent(files, "*/*"))
        }
        labels.add("Delete"); acts.add {
            MaterialAlertDialogBuilder(this).setTitle("Delete this scan?")
                .setMessage("Removes it from the app. Copies already in Downloads/OBD Reports are kept.")
                .setPositiveButton("Delete") { _, _ -> store.delete(e.stamp); onResume() }
                .setNegativeButton("Cancel", null).show()
        }
        MaterialAlertDialogBuilder(this).setTitle(e.date)
            .setItems(labels.toTypedArray()) { _, i -> acts[i]() }
            .show()
    }
}
