package com.nalpakd.obdscanner.ui

import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.nalpakd.obdscanner.data.ReportStore

/** Displays the friendly AI report and exports it as PDF (via Android print), HTML, or share. */
class ReportViewActivity : AppCompatActivity() {

    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = ReportStore(this)
        val stamp = intent.getStringExtra(ScanResultActivity.EXTRA_STAMP) ?: run { finish(); return }
        val html = store.reportHtml(stamp)
        if (!html.exists()) { finish(); return }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = Ui.row(this).apply { val p = Ui.dp(this@ReportViewActivity, 6); setPadding(p, p, p, p) }
        bar.addView(Ui.button(this, "Save PDF") { savePdf(stamp) }, Ui.weighted(this))
        bar.addView(Ui.button(this, "Save HTML", outlined = true) {
            store.exportToDownloads(html, "text/html")
            Toast.makeText(this, "Saved to Downloads/OBD Reports", Toast.LENGTH_SHORT).show()
        }, Ui.weighted(this))
        bar.addView(Ui.button(this, "Share", outlined = true) {
            startActivity(store.shareIntent(listOf(html), "text/html"))
        }, Ui.weighted(this))

        web = WebView(this)
        web.settings.javaScriptEnabled = false
        web.webViewClient = WebViewClient()
        web.loadDataWithBaseURL(null, html.readText(), "text/html", "utf-8", null)

        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    /** Opens Android's print dialog; choose "Save as PDF" to store the report as a PDF on the phone. */
    private fun savePdf(stamp: String) {
        val pm = getSystemService(PrintManager::class.java) ?: return
        val name = "Vehicle_Report_$stamp"
        pm.print(name, web.createPrintDocumentAdapter(name),
            PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.NA_LETTER).build())
    }
}
