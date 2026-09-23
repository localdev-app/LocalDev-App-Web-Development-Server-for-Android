package com.webrun.php

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Spannable
import android.text.SpannableString
import android.text.InputType
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.ConsoleMessage
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.widget.EditText
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.webrun.php.database.NativeDatabaseManager
import com.webrun.php.database.MysqlCompatServer
import com.webrun.php.project.ProjectImporter
import com.webrun.php.runtime.PhpRuntimeManager
import com.webrun.php.runtime.PhpServerManager
import com.webrun.php.sql.SqlWorkspaceManager
import com.webrun.php.web.WebExtractor
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var rootFrame: FrameLayout
    private lateinit var normalUi: LinearLayout
    private lateinit var headerView: View
    private lateinit var tabsView: View
    private lateinit var editor: EditText
    private lateinit var preview: WebView
    private lateinit var console: TextView
    private lateinit var consoleScroll: ScrollView
    private lateinit var status: TextView
    private lateinit var projectLabel: TextView
    private lateinit var fileNameLabel: TextView
    private lateinit var consoleCard: View
    private lateinit var actionsPanel: View
    private lateinit var statusCard: View

    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var fullscreenPreview: WebView
    private lateinit var floatingCard: MaterialCardView
    private lateinit var floatingPreview: WebView

    private lateinit var server: PhpServerManager
    private lateinit var sqlManager: SqlWorkspaceManager
    private lateinit var nativeDb: NativeDatabaseManager
    private lateinit var mysqlCompat: MysqlCompatServer
    private lateinit var webExtractor: WebExtractor
    private var imeVisible = false
    private var suppressEditorWatcher = false

    private val io = Executors.newSingleThreadExecutor()
    private val uiHandler = Handler(Looper.getMainLooper())
    private val tabButtons = linkedMapOf<String, MaterialButton>()

    private var currentTab = "HTML"
    private var currentFile: File? = null
    private var pendingFile: File? = null
    private var importedProject: ProjectImporter.ImportedProject? = null
    private var sqlInfo: SqlWorkspaceManager.ProjectSqlInfo? = null

    private var htmlCode = """
        <main class="shell">
          <p class="eyebrow">LOCALDEV PLAYGROUND</p>
          <h1>Build locally. Test instantly.</h1>
          <p>Edit HTML, CSS, atau JavaScript lalu tekan Run.</p>
          <button onclick="hello()">Test JavaScript</button>
        </main>
    """.trimIndent()

    private var cssCode = """
        :root { color-scheme: dark; }
        * { box-sizing: border-box; }
        body {
          margin: 0;
          min-height: 100vh;
          font-family: system-ui, sans-serif;
          background: #071018;
          color: #f4f8fb;
        }
        .shell { padding: 32px 24px; max-width: 720px; margin: auto; }
        .eyebrow { color: #12e4e4; letter-spacing: .16em; font-size: 12px; }
        h1 { font-size: clamp(32px, 8vw, 58px); margin: 8px 0 12px; }
        p { color: #a8bac7; line-height: 1.6; }
        button {
          margin-top: 12px; padding: 13px 18px; border: 1px solid #12e4e4;
          border-radius: 14px; background: #0d2028; color: #eaffff; font-weight: 700;
        }
    """.trimIndent()

    private var jsCode = """
        function hello() {
          console.log('LocalDev JavaScript runtime OK');
          alert('LocalDev bekerja!');
        }
    """.trimIndent()

    private var phpCode = """
        <?php
        echo "LocalDev PHP playground";
    """.trimIndent()

    private val liveRefresh = Runnable {
        if (::floatingCard.isInitialized && floatingCard.visibility == View.VISIBLE) {
            saveCurrentEditor(showMessage = false)
            refreshWebView(floatingPreview)
        }
    }

    private val openZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        importProject(uri)
    }

    private val openCodeFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        importSingleCodeFile(uri)
    }

    private val exportZip = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        exportCurrentProject(uri)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // LocalDev runs as an immersive editor: status + navigation bars stay hidden.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        enterImmersiveMode()

        sqlManager = SqlWorkspaceManager(this)
        nativeDb = NativeDatabaseManager(this)
        mysqlCompat = MysqlCompatServer()
        webExtractor = WebExtractor(this)

        server = PhpServerManager(
            context = this,
            onLog = { appendConsole(it) },
            onState = { message -> runOnUiThread { status.text = message } }
        )

        rootFrame = FrameLayout(this).apply { setBackgroundColor(color(R.color.localdev_bg)) }
        normalUi = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.localdev_bg))
        }
        rootFrame.addView(normalUi, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        setContentView(rootFrame)

        headerView = buildHeader()
        normalUi.addView(headerView)
        statusCard = buildStatusCard()
        normalUi.addView(statusCard)
        tabsView = buildTabs()
        normalUi.addView(tabsView)
        normalUi.addView(buildWorkspace(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ).apply { topMargin = dp(8) })
        consoleCard = buildConsoleCard()
        actionsPanel = buildActions()
        normalUi.addView(consoleCard)
        normalUi.addView(actionsPanel)

        fullscreenContainer = buildFullscreenPreview()
        rootFrame.addView(fullscreenContainer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        floatingCard = buildFloatingPreview()
        rootFrame.addView(floatingCard, FrameLayout.LayoutParams(dp(190), dp(290), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(112)
            marginEnd = dp(12)
        })

        applySafeArea(normalUi)
        installBackHandler()

        editor.doAfterTextChanged {
            if (!suppressEditorWatcher) {
                uiHandler.removeCallbacks(liveRefresh)
                uiHandler.postDelayed(liveRefresh, 550)
            }
        }

        loadEditorText(htmlCode, "LocalDev Playground", file = null)
        updateTabStyles()
        checkRuntime(showDetails = false)
    }

    private fun buildHeader(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(6))
        }

        val menuButton = TextView(this).apply {
            text = "☰"
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(color(R.color.localdev_text))
            contentDescription = "Menu LocalDev"
            background = roundedDrawable(color(R.color.localdev_surface_2), 12, color(R.color.localdev_outline))
            setOnClickListener { showAppMenu() }
        }
        row.addView(menuButton, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(8) })

        val logo = ImageView(this).apply {
            setImageResource(R.drawable.localdev_logo_mark)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "LocalDev logo"
        }
        row.addView(logo, LinearLayout.LayoutParams(dp(42), dp(42)))

        val titleWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }

        val brand = SpannableString("LocalDev").apply {
            setSpan(ForegroundColorSpan(color(R.color.localdev_cyan)), 5, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        titleWrap.addView(TextView(this).apply {
            text = brand
            setTextColor(color(R.color.localdev_text))
            textSize = 21f
            setTypeface(typeface, Typeface.BOLD)
        })
        titleWrap.addView(TextView(this).apply {
            text = "Code. Run. Test. Anywhere."
            setTextColor(color(R.color.localdev_muted))
            textSize = 10.5f
        })
        row.addView(titleWrap, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        row.addView(TextView(this).apply {
            text = "v1.1.2"
            textSize = 10f
            setTextColor(color(R.color.localdev_cyan))
            gravity = Gravity.CENTER
            background = roundedDrawable(color(R.color.localdev_surface_2), 99, color(R.color.localdev_outline))
            setPadding(dp(10), dp(6), dp(10), dp(6))
        })
        return row
    }

    private fun buildStatusCard(): View {
        val card = MaterialCardView(this).apply {
            radius = dp(15).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(color(R.color.localdev_surface))
            strokeWidth = dp(1)
            strokeColor = color(R.color.localdev_outline)
            setOnClickListener { checkRuntime(showDetails = true) }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(8), dp(13), dp(8))
        }
        row.addView(TextView(this).apply {
            text = "●"
            textSize = 11f
            setTextColor(color(R.color.localdev_success))
        })
        status = TextView(this).apply {
            text = "Memeriksa PHP runtime…"
            textSize = 11.5f
            setTextColor(color(R.color.localdev_text))
            setPadding(dp(7), 0, 0, 0)
            maxLines = 2
        }
        row.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        projectLabel = TextView(this).apply {
            text = "PLAYGROUND"
            textSize = 9.5f
            setTextColor(color(R.color.localdev_muted))
            maxLines = 1
        }
        row.addView(projectLabel)
        card.addView(row)
        return card
    }

    private fun buildTabs(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        listOf("HTML", "CSS", "JS", "PHP").forEachIndexed { index, name ->
            val button = MaterialButton(this).apply {
                text = name
                isAllCaps = false
                textSize = 11.5f
                cornerRadius = dp(13)
                insetTop = 0
                insetBottom = 0
                minHeight = 0
                minimumHeight = 0
                setPadding(dp(6), dp(8), dp(6), dp(8))
                setOnClickListener { switchTab(name) }
            }
            tabButtons[name] = button
            row.addView(button, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                if (index < 3) marginEnd = dp(7)
            })
        }
        return row
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWorkspace(): View {
        val card = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(color(R.color.localdev_surface))
            strokeWidth = dp(1)
            strokeColor = color(R.color.localdev_outline)
        }
        val shell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val fileBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(10), dp(8))
            setBackgroundColor(color(R.color.localdev_surface_2))
        }
        fileBar.addView(ImageView(this).apply { setImageResource(R.drawable.ic_file) }, LinearLayout.LayoutParams(dp(18), dp(18)))
        fileNameLabel = TextView(this).apply {
            text = "  LocalDev Playground"
            textSize = 11.8f
            setTextColor(color(R.color.localdev_text))
            maxLines = 1
        }
        fileBar.addView(fileNameLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fileBar.addView(TextView(this).apply {
            text = "SAVE"
            textSize = 10f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.localdev_cyan))
            setPadding(dp(10), dp(5), dp(10), dp(5))
            setOnClickListener { saveCurrentEditor(showMessage = true) }
        })
        fileBar.addView(TextView(this).apply {
            text = "UTF-8"
            textSize = 9.5f
            setTextColor(color(R.color.localdev_muted))
        })
        shell.addView(fileBar)

        val frame = FrameLayout(this)
        editor = EditText(this).apply {
            gravity = Gravity.TOP or Gravity.START
            typeface = Typeface.MONOSPACE
            textSize = 13.2f
            setTextColor(color(R.color.localdev_text))
            setHintTextColor(color(R.color.localdev_muted))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(14), dp(14), dp(14), dp(18))
            setTextIsSelectable(true)
            isSingleLine = false
            setHorizontallyScrolling(false)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            includeFontPadding = false
            setLineSpacing(dp(2).toFloat(), 1.0f)
        }
        frame.addView(editor, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        preview = createWebView()
        preview.visibility = View.GONE
        frame.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        shell.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        card.addView(shell)
        return card
    }

    private fun buildConsoleCard(): View {
        val card = MaterialCardView(this).apply {
            radius = dp(15).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(color(R.color.localdev_surface))
            strokeWidth = dp(1)
            strokeColor = color(R.color.localdev_outline)
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val titleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(this).apply {
            text = "CONSOLE"
            setTextColor(color(R.color.localdev_cyan))
            setTypeface(typeface, Typeface.BOLD)
            textSize = 10.5f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(TextView(this).apply {
            text = "CLEAR"
            textSize = 9.5f
            setTextColor(color(R.color.localdev_muted))
            setPadding(dp(8), dp(3), 0, dp(3))
            setOnClickListener { console.text = "" }
        })
        wrap.addView(titleRow)
        console = TextView(this).apply {
            text = "LocalDev console ready."
            typeface = Typeface.MONOSPACE
            textSize = 10.2f
            setTextColor(color(R.color.localdev_muted))
            setPadding(0, dp(5), 0, 0)
        }
        consoleScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(console)
        }
        wrap.addView(consoleScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)))
        card.addView(wrap)
        return card.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        }
    }

    private fun buildActions(): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val row1 = LinearLayout(this)
        addAction(row1, "Run", R.drawable.ic_play, primary = true) { runCurrent() }
        addAction(row1, "Preview", R.drawable.ic_info) { openFullscreenPreview() }
        addAction(row1, "Live", R.drawable.ic_terminal) { toggleFloatingPreview() }
        addAction(row1, "Edit", R.drawable.ic_edit) { showEditor(openKeyboard = true) }
        wrap.addView(row1)

        val row2 = LinearLayout(this).apply { setPadding(0, dp(6), 0, 0) }
        addAction(row2, "Files", R.drawable.ic_file) { showProjectBrowser() }
        addAction(row2, "Import", R.drawable.ic_import) { openZip.launch(arrayOf("application/zip", "application/octet-stream")) }
        addAction(row2, "Export", R.drawable.ic_import) { beginExport() }
        addAction(row2, "More", R.drawable.ic_info) { showMoreMenu() }
        wrap.addView(row2)
        return wrap
    }

    private fun addAction(
        row: LinearLayout,
        label: String,
        iconRes: Int,
        primary: Boolean = false,
        danger: Boolean = false,
        action: () -> Unit
    ) {
        val button = MaterialButton(this).apply {
            text = label
            isAllCaps = false
            maxLines = 1
            textSize = 9.8f
            cornerRadius = dp(12)
            insetTop = 0
            insetBottom = 0
            minHeight = 0
            minimumHeight = 0
            icon = AppCompatResources.getDrawable(this@MainActivity, iconRes)
            iconSize = dp(14)
            iconPadding = dp(3)
            iconTint = ColorStateList.valueOf(if (primary) color(R.color.localdev_bg) else color(R.color.localdev_text))
            setTextColor(if (primary) color(R.color.localdev_bg) else color(R.color.localdev_text))
            backgroundTintList = ColorStateList.valueOf(
                when {
                    primary -> color(R.color.localdev_cyan)
                    danger -> Color.rgb(60, 25, 35)
                    else -> color(R.color.localdev_surface_2)
                }
            )
            strokeWidth = dp(1)
            strokeColor = ColorStateList.valueOf(
                when {
                    primary -> color(R.color.localdev_cyan)
                    danger -> color(R.color.localdev_danger)
                    else -> color(R.color.localdev_outline)
                }
            )
            setPadding(dp(5), dp(7), dp(5), dp(7))
            setOnClickListener { action() }
        }
        row.addView(button, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView = WebView(this).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = false
        setBackgroundColor(color(R.color.localdev_bg))
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
        }
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                appendConsole("JS ${message.messageLevel()}: ${message.message()} @${message.lineNumber()}")
                return true
            }
        }
    }

    private fun buildFullscreenPreview(): FrameLayout {
        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        fullscreenPreview = createWebView()
        container.addView(fullscreenPreview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = roundedDrawable(Color.argb(225, 7, 16, 24), 16, color(R.color.localdev_outline))
        }
        controls.addView(miniControl("← Kembali") { exitFullscreenPreview() })
        controls.addView(miniControl("↻ Refresh") { refreshWebView(fullscreenPreview) })
        controls.addView(miniControl("▣ Live") {
            exitFullscreenPreview()
            showFloatingPreview()
        })
        container.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(10)
        })
        return container
    }

    private fun buildFloatingPreview(): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = dp(10).toFloat()
            strokeWidth = dp(1)
            strokeColor = color(R.color.localdev_cyan)
            setCardBackgroundColor(color(R.color.localdev_surface))
            visibility = View.GONE
        }
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), dp(5), dp(6), dp(5))
            setBackgroundColor(color(R.color.localdev_surface_2))
        }
        header.addView(TextView(this).apply {
            text = "● LIVE PREVIEW"
            textSize = 9.5f
            setTextColor(color(R.color.localdev_cyan))
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(miniText("□") { openFullscreenPreview() })
        header.addView(miniText("×") { hideFloatingPreview() })
        installDrag(header, card)
        wrap.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)))

        floatingPreview = createWebView()
        wrap.addView(floatingPreview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        card.addView(wrap)
        return card
    }

    private fun miniControl(label: String, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 10.5f
        setTextColor(color(R.color.localdev_text))
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(7), dp(12), dp(7))
        setOnClickListener { action() }
    }

    private fun miniText(label: String, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 17f
        setTextColor(color(R.color.localdev_text))
        gravity = Gravity.CENTER
        setPadding(dp(7), 0, dp(7), 0)
        setOnClickListener { action() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installDrag(handle: View, target: View) {
        var downX = 0f
        var downY = 0f
        var startX = 0f
        var startY = 0f
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = target.translationX
                    startY = target.translationY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    target.translationX = startX + (event.rawX - downX)
                    target.translationY = startY + (event.rawY - downY)
                    true
                }
                else -> false
            }
        }
    }

    private fun importProject(uri: Uri) {
        status.text = "Mengimpor ZIP project…"
        console.text = "Import project dimulai…"
        io.execute {
            try {
                val dest = File(filesDir, "projects/import_${System.currentTimeMillis()}")
                val project = ProjectImporter.importZip(contentResolver, uri, dest)
                importedProject = project
                val detectedSql = sqlManager.analyze(project.root)
                sqlInfo = detectedSql
                runOnUiThread {
                    projectLabel.text = project.root.name.uppercase().take(18)
                    status.text = when {
                        detectedSql.hasSql && detectedSql.likelyNeedsMysql -> "PHP + SQL project • Run untuk setup database"
                        detectedSql.hasSql -> "Project + SQL terdeteksi"
                        project.isPhp -> "PHP project siap • Run untuk menjalankan"
                        else -> "Static project siap"
                    }
                    appendConsole("Entry: ${project.entryFile.absolutePath}")
                    if (detectedSql.hasSql) {
                        appendConsole("SQL detected: ${detectedSql.sqlFiles.joinToString { it.name }}")
                        if (detectedSql.likelyNeedsMysql) appendConsole("MySQL/PDO reference terdeteksi • tekan Run untuk Setup & Run otomatis")
                    }
                    loadProjectFile(project.entryFile, openKeyboard = false)
                    if (!project.isPhp) showPreviewInline()
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    status.text = "Import gagal"
                    appendConsole("ERROR: ${t.message ?: t.javaClass.simpleName}")
                    toast("Import gagal: ${t.message}")
                }
            }
        }
    }

    private fun importSingleCodeFile(uri: Uri) {
        io.execute {
            try {
                val name = queryDisplayName(uri).ifBlank { "imported.txt" }
                val ext = name.substringAfterLast('.', "txt").lowercase()
                require(ext in setOf("php", "html", "htm", "css", "js", "mjs", "json", "txt", "sql")) { "Format file tidak didukung: .$ext" }
                val root = importedProject?.root ?: File(filesDir, "projects/manual_${System.currentTimeMillis()}").apply { mkdirs() }
                val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val target = File(root, safeName)
                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "File tidak dapat dibaca." }
                    FileOutputStream(target).use { out -> input.copyTo(out) }
                }
                if (importedProject == null) {
                    val entry = if (ext == "php") target else File(root, "index.html").apply {
                        if (!exists()) writeText("<!doctype html><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><h1>LocalDev project</h1>")
                    }
                    importedProject = ProjectImporter.ImportedProject(root, entry, ext == "php")
                }
                runOnUiThread {
                    projectLabel.text = root.name.uppercase().take(18)
                    loadProjectFile(target, openKeyboard = true)
                    appendConsole("File imported: ${target.name}")
                }
            } catch (t: Throwable) {
                runOnUiThread { toast("Import file gagal: ${t.message}") }
            }
        }
    }

    private fun runCurrent() {
        saveCurrentEditor(showMessage = false)
        val project = importedProject
        if (project?.isPhp == true) {
            runImportedPhp(showInline = true)
        } else {
            showPreviewInline()
        }
    }

    private fun runImportedPhp(showInline: Boolean) {
        saveCurrentEditor(showMessage = false)
        val project = importedProject
        if (project == null || !project.isPhp) {
            toast("Import project PHP yang memiliki index.php terlebih dahulu.")
            return
        }

        val info = sqlManager.analyze(project.root)
        sqlInfo = info

        // v0.9.2: MySQL projects use a temporary LocalDev TCP profile in the imported copy, then connect to
        // 127.0.0.1:3306 through Native MariaDB when bundled, otherwise LocalDevDB compatibility.
        if (info.likelyNeedsMysql) {
            status.text = "Memeriksa database lokal…"
            io.execute {
                val php = PhpRuntimeManager(this).diagnostics()
                val db = nativeDb.diagnostics()
                runOnUiThread {
                    if (!php.available) {
                        status.text = "PHP runtime belum tersedia"
                        showTextDialog("PHP runtime", php.error.ifBlank { "PHP runtime belum tersedia." })
                        return@runOnUiThread
                    }

                    if (info.pdoMysqlReferences > 0 && !php.pdoMysql) {
                        status.text = "PDO MySQL belum tersedia"
                        MaterialAlertDialogBuilder(this)
                            .setTitle("PDO MySQL diperlukan")
                            .setMessage(
                                "Source ini memakai PDO MySQL, tetapi PHP runtime build ini belum memiliki pdo_mysql.\n\n" +
                                    "LocalDev membutuhkan PHP ARM64 dengan mysqlnd + pdo_mysql. " +
                                    "Source project tidak akan dipatch otomatis."
                            )
                            .setPositiveButton("Database Info") { _, _ -> showDatabaseMenu() }
                            .setNeutralButton("Run anyway") { _, _ -> startPhpProject(project, showInline) }
                            .setNegativeButton("Batal", null)
                            .show()
                        return@runOnUiThread
                    }

                    if (!db.runtimeBundled) {
                        val localDb = sqlManager.databaseFile(project.root)
                        if (localDb.isFile) {
                            // Re-apply the reversible TCP profile and point LocalDevDB at THIS
                            // project's database. Never reuse READY state from another project.
                            status.text = "Memvalidasi LocalDevDB project…"
                            setupMysqlCompatThenRun(project, null, showInline)
                            return@runOnUiThread
                        }

                        status.text = "LocalDevDB siap disiapkan"
                        MaterialAlertDialogBuilder(this)
                            .setTitle("Setup LocalDevDB")
                            .setMessage(
                                "Native MariaDB ARM64 belum dibundel. Sebagai gantinya LocalDev v0.9.2 dapat menjalankan " +
                                    "server MySQL-compatible sendiri di 127.0.0.1:3306 dengan database lokal.\n\n" +
                                    "PDO MySQL/mysqli tetap terhubung melalui protocol MySQL. LocalDev hanya membuat backup lalu " +
                                    "mengubah host pada COPY project menjadi 127.0.0.1 agar PDO memakai TCP, bukan Unix socket. " +
                                    "Nama database/user/password project tetap dipertahankan dan database.sql di-import ke database lokal.\n\n" +
                                    "Contoh config baru:\n\n${nativeDb.configSnippet()}\n\n" +
                                    "Catatan: LocalDevDB adalah compatibility server, bukan MariaDB asli."
                            )
                            .setPositiveButton("Setup & Run") { _, _ ->
                                setupMysqlCompatThenRun(project, info.sqlFiles.firstOrNull(), showInline)
                            }
                            .setNeutralButton("Database Info") { _, _ -> showDatabaseMenu() }
                            .setNegativeButton("Batal", null)
                            .show()
                        return@runOnUiThread
                    }

                    if (!db.ready) {
                        val sqlFile = info.sqlFiles.firstOrNull()
                        MaterialAlertDialogBuilder(this)
                            .setTitle("Setup Native Database")
                            .setMessage(
                                "LocalDev akan menyalakan MariaDB di 127.0.0.1:3306, membuat database localdev, " +
                                    (if (sqlFile != null) "dan mengimpor ${sqlFile.name}." else "tanpa import SQL karena file .sql tidak ditemukan.") +
                                    "\n\nIsi config website:\n${nativeDb.configSnippet()}"
                            )
                            .setPositiveButton("Setup & Run") { _, _ -> setupNativeDatabaseThenRun(project, sqlFile, showInline) }
                            .setNeutralButton("Copy Config") { _, _ -> copyNativeDatabaseConfig() }
                            .setNegativeButton("Batal", null)
                            .show()
                        return@runOnUiThread
                    }

                    startPhpProject(project, showInline)
                }
            }
            return
        }

        startPhpProject(project, showInline)
    }

    private fun setupMysqlCompatThenRun(
        project: ProjectImporter.ImportedProject,
        sqlFile: File?,
        showInline: Boolean
    ) {
        status.text = "Menyiapkan LocalDevDB…"
        console.text = "Starting LocalDevDB MySQL compatibility…"
        io.execute {
            runCatching {
                val dbFile = sqlManager.databaseFile(project.root)
                if (sqlFile != null) {
                    val report = sqlManager.importSqlToSqlite(project.root, sqlFile)
                    appendConsole(
                        "SQL import: ${report.executed}/${report.totalStatements} executed • " +
                            "${report.failed} failed • ${report.tableCount} tables"
                    )
                    report.errors.take(8).forEach { appendConsole("SQL: $it") }
                    if (report.tableCount <= 0) {
                        throw IllegalStateException("database.sql tidak menghasilkan tabel yang dapat dipakai.")
                    }
                } else if (!dbFile.isFile) {
                    dbFile.parentFile?.mkdirs()
                    android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbFile, null).close()
                }

                // Imported projects commonly use host=localhost. PDO MySQL interprets
                // that as a Unix socket, so LocalDev applies a reversible TCP-only profile
                // to the imported copy (127.0.0.1) and keeps a production backup.
                val profile = sqlManager.applyMysqlTcpProfile(project.root)
                profile.changedFiles.forEach { file ->
                    appendConsole("Local profile: ${file.relativeTo(project.root).invariantSeparatorsPath}")
                }
                profile.notes.forEach { appendConsole(it) }

                nativeDb.stop()
                mysqlCompat.stop()
                mysqlCompat.start(dbFile) { line -> appendConsole(line) }
                if (!mysqlCompat.diagnostics().running) {
                    throw IllegalStateException("LocalDevDB gagal membuka 127.0.0.1:3306.")
                }

                // Never show READY based only on an open port. Prove that the bundled PHP
                // pdo_mysql driver can handshake, SELECT, and use native prepared statements.
                val dbName = sqlManager.analyze(project.root).probableDatabaseNames.firstOrNull() ?: "localdev"
                val tcp = PhpRuntimeManager(this).testMysqlTcp(database = dbName)
                if (!tcp.ok || !tcp.driver.equals("mysql", ignoreCase = true) || tcp.prepared != "prepared-ok") {
                    throw IllegalStateException(
                        "PDO MySQL TCP test gagal: " + tcp.error.ifBlank { tcp.raw.ifBlank { "driver/protocol test gagal" } }
                    )
                }
                appendConsole("PDO MySQL TCP: READY • driver=${tcp.driver} • db=${tcp.database} • ${tcp.version}")
                appendConsole("Native prepared statement: ${tcp.prepared}")
            }.onSuccess {
                runOnUiThread {
                    status.text = "LocalDevDB • 127.0.0.1:3306 • READY"
                    appendConsole("MySQL protocol: READY • logic PHP tetap asli; hanya config lokal project yang dioverride")
                    appendConsole("Config lokal: host=127.0.0.1 port=3306 • nama/user/pass project tetap diterima")
                    startPhpProject(project, showInline)
                }
            }.onFailure { t ->
                runOnUiThread {
                    status.text = "LocalDevDB setup gagal"
                    appendConsole("LocalDevDB ERROR: ${t.message ?: t.javaClass.simpleName}")
                    showTextDialog("LocalDevDB setup gagal", t.message ?: t.javaClass.simpleName)
                }
            }
        }
    }

    private fun setupNativeDatabaseThenRun(
        project: ProjectImporter.ImportedProject,
        sqlFile: File?,
        showInline: Boolean
    ) {
        status.text = "Starting MariaDB…"
        console.text = "Starting LocalDev Native Database…"
        io.execute {
            runCatching {
                nativeDb.setupDefaultDatabase(sqlFile) { line -> appendConsole(line) }
            }.onSuccess { report ->
                runOnUiThread {
                    status.text = "MariaDB • 127.0.0.1:3306 • READY"
                    appendConsole(report.message)
                    appendConsole("DB=localdev USER=localdev HOST=127.0.0.1 PORT=3306")
                    startPhpProject(project, showInline)
                }
            }.onFailure { t ->
                runOnUiThread {
                    status.text = "MariaDB gagal start"
                    appendConsole("NATIVE DB ERROR: ${t.message ?: t.javaClass.simpleName}")
                    showTextDialog("Native Database gagal", t.message ?: t.javaClass.simpleName)
                }
            }
        }
    }

    private fun copyNativeDatabaseConfig() {
        val clip = ClipData.newPlainText("LocalDev DB config", nativeDb.configSnippet())
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(clip)
        toast("Config database LocalDev disalin")
    }

    private fun setupSqlCompatibilityThenRun(
        project: ProjectImporter.ImportedProject,
        sqlFile: File,
        showInline: Boolean
    ) {
        status.text = "Menyiapkan database lokal…"
        console.text = "SQL Auto Setup dimulai…"
        io.execute {
            runCatching {
                val report = sqlManager.importSqlToSqlite(project.root, sqlFile)
                val patch = sqlManager.patchCommonPdoConfig(project.root)
                report to patch
            }.onSuccess { (report, patch) ->
                sqlInfo = sqlManager.analyze(project.root)
                runOnUiThread {
                    appendConsole(
                        "SQL setup OK • tables=${report.tableCount} • executed=${report.executed} • " +
                            "failed=${report.failed} • patched=${patch.changedFiles.size}"
                    )
                    if (report.errors.isNotEmpty()) {
                        appendConsole("SQL warnings:\n" + report.errors.take(6).joinToString("\n"))
                    }
                    patch.warnings.forEach { appendConsole("PATCH: $it") }
                    currentFile?.let { if (it.isFile) loadProjectFile(it, openKeyboard = false) }
                    startPhpProject(project, showInline)
                }
            }.onFailure { t ->
                runOnUiThread {
                    status.text = "Setup database gagal"
                    appendConsole("SQL AUTO SETUP ERROR: ${t.message ?: t.javaClass.simpleName}")
                    showTextDialog(
                        "Database setup gagal",
                        (t.message ?: t.javaClass.simpleName) +
                            "\n\nCoba More → Database / SQL untuk melihat dan menjalankan langkah secara manual."
                    )
                }
            }
        }
    }

    private fun patchSqlConfigThenRun(
        project: ProjectImporter.ImportedProject,
        showInline: Boolean
    ) {
        status.text = "Menyesuaikan config database…"
        io.execute {
            runCatching { sqlManager.patchCommonPdoConfig(project.root) }
                .onSuccess { patch ->
                    sqlInfo = sqlManager.analyze(project.root)
                    runOnUiThread {
                        appendConsole("PDO patch: ${patch.changedFiles.size} file • backup dibuat")
                        patch.warnings.forEach { appendConsole("PATCH: $it") }
                        currentFile?.let { if (it.isFile) loadProjectFile(it, openKeyboard = false) }
                        startPhpProject(project, showInline)
                    }
                }
                .onFailure { t ->
                    runOnUiThread {
                        status.text = "Patch database gagal"
                        appendConsole("PDO PATCH ERROR: ${t.message ?: t.javaClass.simpleName}")
                    }
                }
        }
    }

    private fun startPhpProject(project: ProjectImporter.ImportedProject, showInline: Boolean) {
        val nativeReady = runCatching { nativeDb.diagnostics().portOpen }.getOrDefault(false)
        val compatReady = runCatching { mysqlCompat.diagnostics().running }.getOrDefault(false)
        console.text = when {
            nativeReady -> "Starting LocalDev PHP bridge…\nMariaDB: 127.0.0.1:3306 • READY"
            compatReady -> "Starting LocalDev PHP bridge…\nLocalDevDB MySQL-compatible: 127.0.0.1:3306 • READY"
            sqlManager.hasDatabase(project.root) -> "Starting LocalDev PHP bridge…\nSQLite compatibility file tersedia (MySQL server belum aktif)"
            else -> "Starting LocalDev PHP bridge…"
        }
        server.start(project.documentRoot, project.root) {
            if (showInline) {
                showPreviewInline()
            } else {
                refreshAllVisiblePreviews()
            }
        }
    }

    private fun showPreviewInline() {
        editor.clearFocus()
        WindowInsetsControllerCompat(window, preview).hide(WindowInsetsCompat.Type.ime())
        editor.visibility = View.GONE
        preview.visibility = View.VISIBLE
        ensurePhpServerIfNeeded { refreshWebView(preview) }
    }

    private fun showEditor(openKeyboard: Boolean) {
        preview.visibility = View.GONE
        editor.visibility = View.VISIBLE
        resetEditorViewport(keepCursor = true)
        if (openKeyboard) {
            editor.requestFocus()
            editor.post { WindowInsetsControllerCompat(window, editor).show(WindowInsetsCompat.Type.ime()) }
        }
    }

    private fun openFullscreenPreview() {
        saveCurrentEditor(showMessage = false)
        hideFloatingPreview()
        ensurePhpServerIfNeeded {
            fullscreenContainer.visibility = View.VISIBLE
            fullscreenContainer.bringToFront()
            refreshWebView(fullscreenPreview)
            enterImmersiveMode()
        }
    }

    private fun exitFullscreenPreview() {
        fullscreenContainer.visibility = View.GONE
        // Do not restore Android system bars when leaving preview.
        enterImmersiveMode()
        ViewCompat.requestApplyInsets(normalUi)
    }

    private fun toggleFloatingPreview() {
        if (floatingCard.visibility == View.VISIBLE) hideFloatingPreview() else showFloatingPreview()
    }

    private fun showFloatingPreview() {
        saveCurrentEditor(showMessage = false)
        ensurePhpServerIfNeeded {
            floatingCard.visibility = View.VISIBLE
            floatingCard.bringToFront()
            refreshWebView(floatingPreview)
        }
    }

    private fun hideFloatingPreview() {
        floatingCard.visibility = View.GONE
    }

    private fun ensurePhpServerIfNeeded(action: () -> Unit) {
        val project = importedProject
        if (project?.isPhp == true && !server.isRunning()) {
            server.start(project.documentRoot, project.root) { action() }
        } else {
            action()
        }
    }

    private fun refreshAllVisiblePreviews() {
        if (preview.visibility == View.VISIBLE) refreshWebView(preview)
        if (fullscreenContainer.visibility == View.VISIBLE) refreshWebView(fullscreenPreview)
        if (floatingCard.visibility == View.VISIBLE) refreshWebView(floatingPreview)
    }

    private fun refreshWebView(web: WebView) {
        val project = importedProject
        when {
            project?.isPhp == true && server.isRunning() -> {
                web.loadUrl(PhpServerManager.URL + "?localdev=${System.currentTimeMillis()}")
            }
            project != null && !project.isPhp -> {
                web.loadUrl(project.entryFile.toURI().toString())
            }
            else -> {
                web.loadDataWithBaseURL("https://local.localdev/", playgroundPage(), "text/html", "UTF-8", null)
            }
        }
    }

    private fun playgroundPage(): String = """
        <!doctype html>
        <html><head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width,initial-scale=1">
          <style>$cssCode</style>
        </head><body>
          $htmlCode
          <script>$jsCode</script>
        </body></html>
    """.trimIndent()

    private fun switchTab(name: String) {
        saveCurrentEditor(showMessage = false)
        currentTab = name
        val projectFile = resolveFileForTab(name)
        if (projectFile != null) {
            loadProjectFile(projectFile, openKeyboard = false, forceTab = name)
        } else {
            currentFile = null
            pendingFile = pendingFileForTab(name)
            val code = when (name) {
                "CSS" -> cssCode
                "JS" -> jsCode
                "PHP" -> phpCode
                else -> htmlCode
            }
            val label = pendingFile?.let { "${it.name} (baru)" } ?: "LocalDev Playground"
            loadEditorText(code, label, file = null)
            showEditor(openKeyboard = false)
        }
        status.text = "Editing $name"
        updateTabStyles()
    }

    private fun resolveFileForTab(tab: String): File? {
        val root = importedProject?.root ?: return null
        val all = editableFiles(root)
        val preferredNames = when (tab) {
            "HTML" -> listOf("index.html", "index.htm", "home.html")
            "CSS" -> listOf("style.css", "styles.css", "app.css", "main.css")
            "JS" -> listOf("app.js", "script.js", "main.js", "index.js")
            "PHP" -> listOf("index.php", "app.php")
            else -> emptyList()
        }
        preferredNames.forEach { wanted -> all.firstOrNull { it.name.equals(wanted, true) }?.let { return it } }
        val extensions = when (tab) {
            "HTML" -> setOf("html", "htm")
            "CSS" -> setOf("css")
            "JS" -> setOf("js", "mjs")
            "PHP" -> setOf("php")
            else -> emptySet()
        }
        return all.firstOrNull { it.extension.lowercase() in extensions }
    }

    private fun pendingFileForTab(tab: String): File? {
        val root = importedProject?.root ?: return null
        return when (tab) {
            "CSS" -> File(root, "style.css")
            "JS" -> File(root, "app.js")
            "PHP" -> File(root, "index.php")
            "HTML" -> if (importedProject?.isPhp == true) File(root, "page.html") else File(root, "index.html")
            else -> null
        }
    }

    private fun loadProjectFile(file: File, openKeyboard: Boolean, forceTab: String? = null) {
        if (!file.isFile) return
        val tab = forceTab ?: tabForFile(file)
        currentTab = tab
        currentFile = file
        pendingFile = null
        val content = runCatching { file.readText() }.getOrElse { "/* Gagal membaca file: ${it.message} */" }
        loadEditorText(content, file.name, file)
        updateTabStyles()
        showEditor(openKeyboard)
        status.text = "Editing ${file.name}"
    }

    private fun loadEditorText(value: String, label: String, file: File?) {
        suppressEditorWatcher = true
        editor.setText(value)
        editor.setSelection(0)
        suppressEditorWatcher = false
        currentFile = file
        fileNameLabel.text = "  $label"
        resetEditorViewport(keepCursor = false)
    }

    private fun saveCurrentEditor(showMessage: Boolean) {
        if (!::editor.isInitialized || editor.visibility == View.GONE && currentFile == null && pendingFile == null) return
        val value = editor.text?.toString().orEmpty()
        val target = currentFile ?: pendingFile
        if (target != null) {
            runCatching {
                target.parentFile?.mkdirs()
                target.writeText(value)
                currentFile = target
                pendingFile = null
                fileNameLabel.text = "  ${target.name}"
            }.onFailure { appendConsole("Save error: ${it.message}") }
        }
        when (currentTab) {
            "CSS" -> cssCode = value
            "JS" -> jsCode = value
            "PHP" -> phpCode = value
            else -> htmlCode = value
        }
        if (showMessage) toast(if (target != null) "${target.name} tersimpan" else "Playground tersimpan")
    }

    private fun showAppMenu() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = roundedDrawable(color(R.color.localdev_surface), 22, color(R.color.localdev_outline))
        }

        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        brandRow.addView(ImageView(this).apply {
            setImageResource(R.drawable.localdev_logo_mark)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(12) })

        val brandText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brandText.addView(TextView(this).apply {
            text = "LocalDev"
            textSize = 21f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.localdev_text))
        })
        brandText.addView(TextView(this).apply {
            text = "Web development workspace • v1.1.2"
            textSize = 10.5f
            setTextColor(color(R.color.localdev_muted))
        })
        brandRow.addView(brandText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        brandRow.addView(TextView(this).apply {
            text = "✕"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(color(R.color.localdev_muted))
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(dp(38), dp(38)))
        panel.addView(brandRow)

        panel.addView(TextView(this).apply {
            text = importedProject?.let { "Project aktif  •  ${it.root.name}" } ?: "Belum ada project aktif"
            textSize = 10.5f
            setTextColor(if (importedProject != null) color(R.color.localdev_success) else color(R.color.localdev_muted))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedDrawable(color(R.color.localdev_surface_2), 13, color(R.color.localdev_outline))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
            bottomMargin = dp(12)
        })

        val scroller = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        fun section(label: String) {
            list.addView(TextView(this).apply {
                text = label.uppercase()
                textSize = 9f
                setTypeface(typeface, Typeface.BOLD)
                letterSpacing = .12f
                setTextColor(color(R.color.localdev_muted))
                setPadding(dp(4), dp(12), dp(4), dp(6))
            })
        }

        fun item(icon: String, title: String, subtitle: String, accent: Boolean = false, action: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = roundedDrawable(
                    if (accent) Color.rgb(9, 53, 60) else color(R.color.localdev_surface_2),
                    14,
                    if (accent) color(R.color.localdev_cyan_dark) else color(R.color.localdev_outline)
                )
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dialog.dismiss()
                    action()
                }
            }
            row.addView(TextView(this).apply {
                text = icon
                textSize = 18f
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(34), dp(38)).apply { marginEnd = dp(9) })

            val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            copy.addView(TextView(this).apply {
                text = title
                textSize = 12.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(color(R.color.localdev_text))
            })
            copy.addView(TextView(this).apply {
                text = subtitle
                textSize = 9.5f
                setTextColor(color(R.color.localdev_muted))
                maxLines = 2
            })
            row.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = "›"
                textSize = 22f
                setTextColor(if (accent) color(R.color.localdev_cyan) else color(R.color.localdev_muted))
            })
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(7)
            })
        }

        section("Project")
        item("📁", "Project Files", "Lihat, buka, buat file, dan kelola folder project.") { showProjectBrowser() }
        item("＋", "Project Baru", "Mulai project PHP atau website statis dari nol.") { showCreateProjectDialog() }
        item("⇩", "Import ZIP", "Import source website lengkap dari arsip ZIP.") {
            openZip.launch(arrayOf("application/zip", "application/octet-stream"))
        }
        item("⇧", "Export Project ZIP", "Simpan project aktif kembali sebagai ZIP.") { beginExport() }

        section("Tools")
        item("🌐", "Web Extractor", "Ambil HTML, CSS, gambar, font, dan asset dari URL publik.", accent = true) {
            showWebExtractorDialog()
        }
        item("🗄", "Database", "SQL workspace, import database.sql, dan LocalDevDB.") { showDatabaseMenu() }
        item("⌁", "PHP Runtime", "Cek versi PHP dan extension runtime.") { checkRuntime(showDetails = true) }

        section("LocalDev")
        item("ⓘ", "Tentang LocalDev", "Informasi aplikasi dan tujuan LocalDev.") {
            showLegalPage("Tentang LocalDev", aboutLocalDevText())
        }
        item("🔒", "Privasi", "Kebijakan privasi dan pemrosesan data lokal.") {
            showLegalPage("Kebijakan Privasi", privacyPolicyText())
        }
        item("📄", "Syarat & Ketentuan", "Ketentuan penggunaan LocalDev.") {
            showLegalPage("Syarat & Ketentuan", termsText())
        }
        item("⚠", "Disclaimer", "Batas tanggung jawab penggunaan aplikasi.") {
            showLegalPage("Disclaimer", disclaimerText())
        }
        item("⚙", "Lisensi & Komponen", "Komponen open-source dan informasi lisensi.") {
            showLegalPage("Lisensi & Komponen", licensesText())
        }

        scroller.addView(list)
        panel.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        dialog.setContentView(panel)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setDimAmount(0.62f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply {
                width = (resources.displayMetrics.widthPixels * 0.92f).toInt()
                height = (resources.displayMetrics.heightPixels * 0.90f).toInt()
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                x = dp(8)
            }
        }
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.92f).toInt(),
                (resources.displayMetrics.heightPixels * 0.90f).toInt()
            )
        }
        dialog.show()
    }

    private fun showWebExtractorDialog() {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), 0)
        }

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = roundedDrawable(Color.rgb(7, 43, 51), 16, color(R.color.localdev_cyan_dark))
        }
        hero.addView(TextView(this).apply {
            text = "🌐  Web Extractor"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.localdev_text))
        })
        hero.addView(TextView(this).apply {
            text = "Ambil HTML + stylesheet CSS dari website publik lalu jadikan project LocalDev yang bisa diedit dan diekspor."
            textSize = 10.5f
            setTextColor(color(R.color.localdev_muted))
            setPadding(0, dp(5), 0, 0)
        })
        wrap.addView(hero, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        })

        wrap.addView(TextView(this).apply {
            text = "URL WEBSITE"
            textSize = 9f
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = .10f
            setTextColor(color(R.color.localdev_muted))
            setPadding(dp(2), 0, 0, dp(5))
        })

        val input = EditText(this).apply {
            hint = "https://contoh.com"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(color(R.color.localdev_text))
            setHintTextColor(color(R.color.localdev_muted))
            setPadding(dp(13), dp(12), dp(13), dp(12))
            background = roundedDrawable(color(R.color.localdev_surface_2), 14, color(R.color.localdev_outline))
        }
        wrap.addView(input)

        val assetCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = roundedDrawable(color(R.color.localdev_surface_2), 14, color(R.color.localdev_outline))
        }
        val assetOption = CheckBox(this).apply {
            text = "Download gambar, font & asset CSS"
            setTextColor(color(R.color.localdev_text))
            buttonTintList = ColorStateList.valueOf(color(R.color.localdev_cyan))
            isChecked = true
            textSize = 11f
        }
        assetCard.addView(assetOption)
        assetCard.addView(TextView(this).apply {
            text = "Jika aktif, resource yang bisa diambil disimpan ke folder assets agar preview lebih mandiri."
            textSize = 9.5f
            setTextColor(color(R.color.localdev_muted))
            setPadding(dp(32), 0, 0, 0)
        })
        wrap.addView(assetCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })

        val note = TextView(this).apply {
            text = "HTTPS diverifikasi memakai CA bundle bawaan LocalDev. Website yang UI-nya dibuat sepenuhnya setelah JavaScript berjalan mungkin hanya menghasilkan HTML awal dari server."
            setTextColor(color(R.color.localdev_muted))
            textSize = 9.5f
            setLineSpacing(dp(1).toFloat(), 1.1f)
            setPadding(dp(3), dp(10), dp(3), 0)
        }
        wrap.addView(note)

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(wrap)
            .setPositiveButton("Mulai Extract", null)
            .setNegativeButton("Batal", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).apply {
                setTextColor(color(R.color.localdev_cyan))
                setOnClickListener {
                    var url = input.text?.toString()?.trim().orEmpty()
                    if (url.isBlank()) {
                        input.error = "Masukkan URL website"
                        return@setOnClickListener
                    }
                    if (!url.matches(Regex("^https?://.+", RegexOption.IGNORE_CASE))) {
                        url = "https://$url"
                    }
                    val parsed = runCatching { Uri.parse(url) }.getOrNull()
                    if (parsed == null || parsed.host.isNullOrBlank() || parsed.scheme?.lowercase() !in setOf("http", "https")) {
                        input.error = "URL harus HTTP/HTTPS yang valid"
                        return@setOnClickListener
                    }
                    dialog.dismiss()
                    beginWebExtraction(url, assetOption.isChecked)
                }
            }
        }
        dialog.show()
        input.requestFocus()
    }

    private fun beginWebExtraction(url: String, downloadAssets: Boolean) {
        saveCurrentEditor(showMessage = false)
        status.text = "Mengambil HTML + CSS…"
        console.text = "Web Extractor dimulai…\nURL: $url\nAssets: ${if (downloadAssets) "DOWNLOAD" else "ONLINE URL"}"

        io.execute {
            val root = File(filesDir, "projects/web_${System.currentTimeMillis()}")
            val report = webExtractor.extract(url, root, downloadAssets)
            runOnUiThread {
                if (!report.ok || report.htmlFile == null) {
                    root.deleteRecursively()
                    status.text = "Web Extractor gagal"
                    appendConsole("WEB EXTRACT ERROR: ${report.error}")
                    report.warnings.take(8).forEach { appendConsole("WARN: $it") }
                    showTextDialog(
                        "Web Extractor gagal",
                        report.error + if (report.warnings.isNotEmpty()) "\n\n" + report.warnings.take(10).joinToString("\n") else ""
                    )
                    return@runOnUiThread
                }

                server.stop()
                mysqlCompat.stop()
                runCatching { nativeDb.stop() }
                importedProject = ProjectImporter.ImportedProject(
                    root = root,
                    entryFile = report.htmlFile,
                    isPhp = false,
                    documentRoot = root
                )
                sqlInfo = sqlManager.analyze(root)
                val host = runCatching { Uri.parse(report.finalUrl).host.orEmpty() }.getOrDefault("")
                projectLabel.text = host.ifBlank { "WEB-EXTRACT" }.uppercase().take(18)
                status.text = "Web Extract selesai • ${report.cssFiles.size} CSS • ${report.assetCount} asset"
                console.text = buildString {
                    append("Web Extractor READY\n")
                    append("Final URL: ${report.finalUrl}\n")
                    append("HTML: index.html\n")
                    append("CSS: ${report.cssFiles.size}\n")
                    append("Assets: ${report.assetCount} • ${formatBytes(report.assetBytes)}")
                    report.warnings.take(8).forEach { append("\nWARN: $it") }
                }
                loadProjectFile(report.htmlFile, openKeyboard = false)
                showWebExtractResult(report)
            }
        }
    }

    private fun showWebExtractResult(report: WebExtractor.Report) {
        val items = arrayOf(
            "▶ Preview HTML",
            "📄 Buka index.html",
            "🎨 Lihat CSS • ${report.cssFiles.size} file",
            "⧉ Copy Source HTML",
            "⬆ Download ZIP",
            "📂 Project Files"
        )
        val title = report.title.ifBlank { "Web Extract selesai" }.take(72)
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showPreviewInline()
                    1 -> report.htmlFile?.let { loadProjectFile(it, openKeyboard = false) }
                    2 -> showExtractedCssMenu(report)
                    3 -> copyExtractedHtml(report)
                    4 -> beginExport()
                    5 -> showProjectBrowser()
                }
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun showExtractedCssMenu(report: WebExtractor.Report) {
        if (report.cssFiles.isEmpty()) {
            toast("CSS tidak ditemukan pada halaman tersebut")
            return
        }
        val root = importedProject?.root ?: return
        val labels = report.cssFiles.map { file ->
            runCatching { file.relativeTo(root).invariantSeparatorsPath }.getOrDefault(file.name) + "  " + formatBytes(file.length())
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("CSS hasil extract")
            .setItems(labels) { _, which ->
                report.cssFiles.getOrNull(which)?.let { loadProjectFile(it, openKeyboard = false, forceTab = "CSS") }
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun copyExtractedHtml(report: WebExtractor.Report) {
        val file = report.htmlFile
        if (file == null || !file.isFile) {
            toast("index.html tidak ditemukan")
            return
        }
        runCatching {
            val source = file.readText()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("LocalDev extracted HTML", source))
        }.onSuccess {
            toast("Source HTML disalin")
        }.onFailure {
            toast("Gagal menyalin source: ${it.message}")
        }
    }

    private fun showCreateProjectDialog() {
        val items = arrayOf(
            "PHP Project • index.php",
            "Static Web • index.html"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("Project Baru")
            .setItems(items) { _, which -> createBlankProject(isPhp = which == 0) }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun createBlankProject(isPhp: Boolean) {
        saveCurrentEditor(showMessage = false)
        runCatching {
            val root = File(filesDir, "projects/manual_${System.currentTimeMillis()}").apply { mkdirs() }
            val entry = File(root, if (isPhp) "index.php" else "index.html")
            entry.writeText(
                if (isPhp) defaultNewFileContent("index.php")
                else defaultNewFileContent("index.html")
            )
            server.stop()
            mysqlCompat.stop()
            importedProject = ProjectImporter.ImportedProject(
                root = root,
                entryFile = entry,
                isPhp = isPhp,
                documentRoot = root
            )
            sqlInfo = sqlManager.analyze(root)
            projectLabel.text = root.name.uppercase().take(18)
            status.text = if (isPhp) "PHP project baru siap" else "Static project baru siap"
            console.text = "Project baru dibuat: ${root.absolutePath}"
            loadProjectFile(entry, openKeyboard = true)
            toast("Project baru dibuat")
        }.onFailure { t ->
            appendConsole("Create project error: ${t.message}")
            toast("Gagal membuat project: ${t.message}")
        }
    }

    private fun showProjectBrowser(directory: File? = null) {
        val project = importedProject
        if (project == null) {
            val items = arrayOf(
                "＋ Buat PHP Project",
                "＋ Buat Static Project",
                "⬇ Import ZIP"
            )
            MaterialAlertDialogBuilder(this)
                .setTitle("Project Files • belum ada project")
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> createBlankProject(isPhp = true)
                        1 -> createBlankProject(isPhp = false)
                        2 -> openZip.launch(arrayOf("application/zip", "application/octet-stream"))
                    }
                }
                .setNegativeButton("Tutup", null)
                .show()
            return
        }

        saveCurrentEditor(showMessage = false)
        val root = project.root.canonicalFile
        val current = runCatching { (directory ?: root).canonicalFile }.getOrDefault(root)
        if (!current.path.startsWith(root.path) || !current.isDirectory) {
            toast("Folder project tidak valid")
            return
        }

        val children = current.listFiles().orEmpty()
            .filter { !it.name.startsWith(".localdev") }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))

        val labels = mutableListOf<String>()
        val targets = mutableListOf<File?>()
        val actionKinds = mutableListOf<String>()

        labels += "＋ Buat File Baru"
        targets += null
        actionKinds += "new_file"

        labels += "📁＋ Buat Folder Baru"
        targets += null
        actionKinds += "new_folder"

        if (current != root) {
            labels += "⬅ .."
            targets += current.parentFile
            actionKinds += "parent"
        }

        children.forEach { file ->
            val marker = when {
                file.isDirectory -> "📁"
                isEditableProjectFile(file) -> "📄"
                file.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif", "svg", "ico") -> "🖼"
                else -> "•"
            }
            val suffix = if (file.isFile) "  ${formatBytes(file.length())}" else ""
            labels += "$marker ${file.name}$suffix"
            targets += file
            actionKinds += "target"
        }

        val relative = if (current == root) "/" else "/" + current.relativeTo(root).invariantSeparatorsPath
        val folderCount = children.count { it.isDirectory }
        val fileCount = children.count { it.isFile }

        MaterialAlertDialogBuilder(this)
            .setTitle("Project Files • $relative\n$folderCount folder • $fileCount file")
            .setItems(labels.toTypedArray()) { dialog, which ->
                val kind = actionKinds.getOrNull(which) ?: return@setItems
                val target = targets.getOrNull(which)
                dialog.dismiss()
                when (kind) {
                    "new_file" -> showCreateProjectFileDialog(current, root)
                    "new_folder" -> showCreateProjectFolderDialog(current, root)
                    "parent" -> target?.let { showProjectBrowser(it) }
                    else -> when {
                        target == null -> Unit
                        target.isDirectory -> showProjectBrowser(target)
                        isEditableProjectFile(target) -> loadProjectFile(target, openKeyboard = true)
                        else -> showProjectFileInfo(target, root)
                    }
                }
            }
            .setNeutralButton("Root") { _, _ -> showProjectBrowser(root) }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun showCreateProjectFileDialog(directory: File, root: File) {
        val input = EditText(this).apply {
            hint = "contoh: index.php, style.css, app.js"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setTextColor(color(R.color.localdev_text))
            setHintTextColor(color(R.color.localdev_muted))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Buat File Baru")
            .setView(input)
            .setPositiveButton("Buat", null)
            .setNegativeButton("Batal", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text?.toString()?.trim().orEmpty()
                val error = validateNewProjectName(name)
                if (error != null) {
                    input.error = error
                    return@setOnClickListener
                }

                val target = runCatching { File(directory, name).canonicalFile }.getOrNull()
                if (target == null || target.parentFile?.canonicalFile != directory.canonicalFile || !target.path.startsWith(root.canonicalPath)) {
                    input.error = "Lokasi file tidak valid"
                    return@setOnClickListener
                }
                if (target.exists()) {
                    input.error = "File sudah ada"
                    return@setOnClickListener
                }

                runCatching {
                    target.parentFile?.mkdirs()
                    target.writeText(defaultNewFileContent(name))
                }.onSuccess {
                    dialog.dismiss()
                    refreshImportedProjectForNewEntry(target)
                    loadProjectFile(target, openKeyboard = true)
                    appendConsole("File dibuat: ${target.relativeTo(root).invariantSeparatorsPath}")
                    toast("${target.name} dibuat")
                }.onFailure { t ->
                    input.error = t.message ?: "Gagal membuat file"
                }
            }
        }
        dialog.show()
        input.requestFocus()
    }

    private fun showCreateProjectFolderDialog(directory: File, root: File) {
        val input = EditText(this).apply {
            hint = "contoh: admin, assets, api"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setTextColor(color(R.color.localdev_text))
            setHintTextColor(color(R.color.localdev_muted))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Buat Folder Baru")
            .setView(input)
            .setPositiveButton("Buat", null)
            .setNegativeButton("Batal", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text?.toString()?.trim().orEmpty()
                val error = validateNewProjectName(name)
                if (error != null) {
                    input.error = error
                    return@setOnClickListener
                }

                val target = runCatching { File(directory, name).canonicalFile }.getOrNull()
                if (target == null || target.parentFile?.canonicalFile != directory.canonicalFile || !target.path.startsWith(root.canonicalPath)) {
                    input.error = "Lokasi folder tidak valid"
                    return@setOnClickListener
                }
                if (target.exists()) {
                    input.error = "Folder/file sudah ada"
                    return@setOnClickListener
                }

                if (target.mkdirs()) {
                    dialog.dismiss()
                    appendConsole("Folder dibuat: ${target.relativeTo(root).invariantSeparatorsPath}")
                    showProjectBrowser(target)
                } else {
                    input.error = "Gagal membuat folder"
                }
            }
        }
        dialog.show()
        input.requestFocus()
    }

    private fun validateNewProjectName(name: String): String? {
        if (name.isBlank()) return "Nama wajib diisi"
        if (name.length > 160) return "Nama terlalu panjang"
        if (name == "." || name == "..") return "Nama tidak valid"
        if ('/' in name || '\\' in name || '\u0000' in name) return "Gunakan nama file saja, tanpa path"
        return null
    }

    private fun defaultNewFileContent(name: String): String {
        val lower = name.lowercase()
        return when {
            lower == ".htaccess" -> "# LocalDev / Apache-compatible rewrite rules\n"
            lower.endsWith(".php") || lower.endsWith(".phtml") -> "<?php\n\ndeclare(strict_types=1);\n\n"
            lower.endsWith(".html") || lower.endsWith(".htm") -> """<!doctype html>
<html lang="id">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>LocalDev</title>
</head>
<body>

</body>
</html>
"""
            lower.endsWith(".css") -> "/* LocalDev stylesheet */\n"
            lower.endsWith(".js") || lower.endsWith(".mjs") -> "// LocalDev JavaScript\n"
            lower.endsWith(".json") -> "{\n  \n}\n"
            lower.endsWith(".sql") -> "-- LocalDev SQL\n"
            lower.endsWith(".md") -> "# ${name.substringBeforeLast('.').ifBlank { "LocalDev" }}\n"
            else -> ""
        }
    }

    private fun refreshImportedProjectForNewEntry(file: File) {
        val project = importedProject ?: return
        val root = project.root.canonicalFile
        val rel = runCatching { file.canonicalFile.relativeTo(root).invariantSeparatorsPath.lowercase() }.getOrNull() ?: return
        val isPreferredPhpEntry = rel == "index.php" || rel == "public/index.php"
        val isPreferredHtmlEntry = rel in setOf("index.html", "index.htm", "public/index.html", "public/index.htm")

        when {
            isPreferredPhpEntry -> {
                val documentRoot = file.parentFile ?: root
                importedProject = project.copy(entryFile = file, isPhp = true, documentRoot = documentRoot)
                sqlInfo = sqlManager.analyze(root)
                status.text = "PHP entry aktif • ${file.name}"
            }
            isPreferredHtmlEntry && !project.isPhp -> {
                val documentRoot = file.parentFile ?: root
                importedProject = project.copy(entryFile = file, isPhp = false, documentRoot = documentRoot)
                status.text = "Static entry aktif • ${file.name}"
            }
        }
    }

    private fun showProjectFileInfo(file: File, root: File) {
        val relative = runCatching { file.relativeTo(root).invariantSeparatorsPath }.getOrDefault(file.name)
        val message = buildString {
            append("File: $relative\n")
            append("Ukuran: ${formatBytes(file.length())}\n")
            append("Tipe: ${file.extension.ifBlank { "tanpa ekstensi" }}\n\n")
            append("File biner/media ditampilkan di daftar project, tetapi tidak dibuka di editor teks agar datanya tidak rusak.")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(file.name)
            .setMessage(message)
            .setPositiveButton("Tutup", null)
            .show()
    }

    private fun isEditableProjectFile(file: File): Boolean {
        if (!file.isFile || file.length() > 4L * 1024L * 1024L) return false
        val allowed = setOf(
            "php", "phtml", "html", "htm", "css", "scss", "sass", "less", "js", "mjs", "cjs",
            "ts", "tsx", "jsx", "vue", "svelte", "json", "xml", "txt", "md", "sql", "csv", "log",
            "ini", "conf", "config", "env", "yaml", "yml", "toml", "svg", "webmanifest", "htaccess", "sh"
        )
        val specialNames = setOf(
            ".htaccess", ".env", ".env.example", ".user.ini", "composer.json", "composer.lock",
            "robots.txt", "manifest.json", "package.json", "license", "dockerfile", "makefile"
        )
        return file.name.lowercase() in specialNames || file.extension.lowercase() in allowed
    }

    private fun editableFiles(root: File): List<File> {
        return root.walkTopDown()
            .filter { isEditableProjectFile(it) && !it.relativeTo(root).invariantSeparatorsPath.startsWith(".localdev") }
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath.lowercase() }
            .toList()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    private fun showLegalPage(title: String, content: String) {
        val textView = TextView(this).apply {
            text = content
            setTextColor(color(R.color.localdev_text))
            textSize = 12.5f
            setLineSpacing(dp(2).toFloat(), 1.12f)
            setTextIsSelectable(true)
            setPadding(dp(18), dp(8), dp(18), dp(20))
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            addView(textView)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("Tutup", null)
            .show()
    }

    private fun aboutLocalDevText(): String = """
        LocalDev
        Code. Run. Test. Anywhere.

        Versi 1.1.0

        LocalDev adalah lingkungan pengembangan web lokal untuk Android. Aplikasi ini dirancang untuk membantu pengguna mengimpor project ZIP, melihat dan mengedit source, menjalankan HTML/CSS/JavaScript/PHP, menggunakan preview lokal, serta melakukan pengujian database yang didukung runtime LocalDev.

        Fitur utama:
        • Project ZIP import/export
        • Web Extractor: ambil HTML, CSS eksternal/inline, dan asset opsional dari URL publik
        • File browser project
        • Editor HTML, CSS, JavaScript, PHP, SQL, JSON, konfigurasi, dan file teks umum
        • PHP 8.x ARM64 runtime
        • Local preview di 127.0.0.1:8080
        • Live / fullscreen preview
        • PDO MySQL, mysqli, SQLite, cURL, OpenSSL, dan ZIP sesuai runtime yang tersedia
        • LocalDevDB compatibility untuk pengujian project MySQL

        LocalDev dibuat sebagai development/testing environment. Kompatibilitas dapat berbeda untuk project yang membutuhkan extension, service, framework, binary, atau konfigurasi server tertentu.
    """.trimIndent()

    private fun privacyPolicyText(): String = """
        KEBIJAKAN PRIVASI LOCALDEV
        Berlaku untuk LocalDev 1.1.0

        1. Penyimpanan project
        Project yang Anda import disalin ke penyimpanan aplikasi LocalDev pada perangkat. LocalDev tidak dirancang untuk mengunggah source project Anda ke server pengembang.

        2. Data yang diproses
        LocalDev memproses file project, source code, database lokal, log console, dan konfigurasi runtime hanya untuk menjalankan fitur development pada perangkat.

        3. Akses jaringan
        LocalDev memiliki izin INTERNET karena preview localhost dan source yang Anda jalankan dapat memerlukan jaringan. Kode PHP/JavaScript milik pengguna dapat melakukan request ke layanan pihak ketiga. Request yang dibuat oleh source project tersebut berada di luar kendali LocalDev.

        4. Web Extractor
        Saat Anda menggunakan Web Extractor, LocalDev melakukan request langsung dari perangkat Anda ke URL yang Anda masukkan dan resource publik yang dirujuk halaman tersebut. LocalDev tidak mengirim source hasil ekstraksi ke server pengembang LocalDev.

        5. Data sensitif
        Jangan menyimpan credential production, API key, password, token, atau database pribadi di project yang akan dibagikan. Gunakan credential testing/local bila memungkinkan.

        6. Penghapusan data
        Project dapat dihapus dengan menghapus data aplikasi/uninstall. File ZIP yang Anda export berada pada lokasi yang Anda pilih melalui Android Storage Access Framework.

        7. Pihak ketiga
        Runtime dan library pihak ketiga dapat memiliki lisensi dan kebijakan masing-masing. LocalDev tidak bertanggung jawab atas data yang sengaja dikirim oleh source project pengguna ke layanan eksternal.
    """.trimIndent()

    private fun termsText(): String = """
        SYARAT & KETENTUAN

        Dengan menggunakan LocalDev, Anda setuju bahwa:

        1. LocalDev digunakan untuk development, pembelajaran, debugging, dan testing source yang Anda miliki atau berhak Anda gunakan.
        2. Web Extractor hanya digunakan untuk website/content yang Anda miliki, berlisensi, atau telah mendapat izin untuk disalin/dianalisis. Anda bertanggung jawab menghormati hak cipta, robots policy yang relevan, dan ketentuan layanan website sumber.
        3. Anda bertanggung jawab atas source code, database, credential, API, dan konten yang Anda import atau jalankan.
        4. Anda tidak boleh mengandalkan LocalDev sebagai pengganti environment production tanpa pengujian tambahan.
        5. Fitur compatibility server tidak menjamin perilaku identik dengan Apache, Nginx, PHP-FPM, MySQL, atau MariaDB asli.
        6. Anda bertanggung jawab membuat backup sebelum mengedit atau meng-export project penting.
        7. Anda bertanggung jawab mematuhi hukum, lisensi software, serta ketentuan layanan pihak ketiga yang digunakan oleh project Anda.

        Penggunaan aplikasi berarti Anda menerima batasan teknis tersebut.
    """.trimIndent()

    private fun disclaimerText(): String = """
        DISCLAIMER

        LocalDev disediakan sebagai alat development/testing. Tidak ada jaminan bahwa setiap source PHP, framework, CMS, .htaccess rule, extension, database dump, atau aplikasi web akan berjalan identik dengan server production.

        LocalDev tidak bertanggung jawab atas kehilangan data, kerusakan project, perubahan konfigurasi, koneksi ke layanan eksternal, atau konsekuensi yang berasal dari kode yang dijalankan pengguna.

        Gunakan salinan project untuk testing dan simpan backup sebelum melakukan perubahan penting. Jangan gunakan database atau credential production untuk eksperimen lokal kecuali Anda memahami risikonya.
    """.trimIndent()

    private fun licensesText(): String = """
        LISENSI & KOMPONEN

        LocalDev menggunakan komponen AndroidX dan Material Components for Android.

        Runtime PHP yang dibundel pada build fallback berasal dari proyek PHP binaries yang digunakan LocalDev sesuai konfigurasi build project. PHP dan library terkait tetap tunduk pada lisensi masing-masing.

        Beberapa build dapat menggunakan runtime/database tambahan yang dibundel secara terpisah. Lisensi komponen pihak ketiga tetap dimiliki oleh masing-masing pemegang hak cipta.

        Source project yang Anda import tidak menjadi milik LocalDev. Pastikan Anda memiliki izin untuk menggunakan, mengubah, dan menjalankan source tersebut.
    """.trimIndent()

    private fun tabForFile(file: File): String = when (file.extension.lowercase()) {
        "css", "scss", "sass", "less" -> "CSS"
        "js", "mjs", "cjs", "ts", "tsx", "jsx", "vue", "svelte" -> "JS"
        "php", "phtml", "sql" -> "PHP"
        else -> "HTML"
    }

    private fun beginExport() {
        saveCurrentEditor(showMessage = false)
        val suggested = (importedProject?.root?.name ?: "LocalDev-project").replace(Regex("[^A-Za-z0-9._-]"), "_") + ".zip"
        exportZip.launch(suggested)
    }

    private fun exportCurrentProject(uri: Uri) {
        status.text = "Mengekspor project…"
        io.execute {
            try {
                val root = importedProject?.root ?: createPlaygroundExportRoot()
                contentResolver.openOutputStream(uri, "w").use { raw ->
                    requireNotNull(raw) { "Tujuan export tidak dapat dibuka." }
                    ZipOutputStream(raw.buffered()).use { zip ->
                        root.walkTopDown().filter { it.isFile }.forEach { file ->
                            val relative = file.relativeTo(root).invariantSeparatorsPath
                            if (relative.startsWith(".localdev/") || relative.startsWith(".localdev-backup/")) return@forEach
                            zip.putNextEntry(ZipEntry(relative))
                            file.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
                runOnUiThread {
                    status.text = "Export ZIP berhasil"
                    toast("Project berhasil diekspor")
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    status.text = "Export gagal"
                    toast("Export gagal: ${t.message}")
                }
            }
        }
    }

    private fun createPlaygroundExportRoot(): File {
        val root = File(cacheDir, "localdev-export").apply {
            deleteRecursively()
            mkdirs()
        }
        File(root, "index.html").writeText("""
            <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <link rel="stylesheet" href="style.css"></head><body>$htmlCode<script src="app.js"></script></body></html>
        """.trimIndent())
        File(root, "style.css").writeText(cssCode)
        File(root, "app.js").writeText(jsCode)
        File(root, "index.php").writeText(phpCode)
        return root
    }

    private fun showDatabaseMenu() {
        val root = importedProject?.root
        if (root == null) {
            toast("Import project terlebih dahulu.")
            return
        }
        saveCurrentEditor(showMessage = false)
        val info = sqlManager.analyze(root)
        sqlInfo = info
        val legacy = sqlManager.databaseFile(root)
        val d = nativeDb.diagnostics()
        val compat = mysqlCompat.diagnostics()
        val php = PhpRuntimeManager(this).diagnostics(timeoutSeconds = 4)

        val nativeStatus = when {
            d.ready -> "RUNNING • 127.0.0.1:3306"
            d.runtimeBundled -> "BUNDLED • stopped/not initialized"
            else -> "NOT BUNDLED"
        }
        val message = buildString {
            append("Native MariaDB: $nativeStatus\n")
            append("LocalDevDB MySQL-compatible: ${if (compat.running) "RUNNING • 127.0.0.1:3306" else "STOPPED"}\n")
            append("PDO MySQL: ${mark(php.pdoMysql)} • mysqli: ${mark(php.mysqli)}\n")
            append("SQL files: ${info.sqlFiles.size}\n")
            append("\nLocalDev DB profile\n")
            append("Host: localhost\nPort: 3306\nDatabase: localdev\nUser: localdev\nPassword: localdev\nCharset: utf8mb4")
            if (info.probableDatabaseNames.isNotEmpty()) {
                append("\n\nProject DB reference: ${info.probableDatabaseNames.joinToString()}")
            }
            if (legacy.isFile) append("\nLegacy SQLite: ${legacy.length() / 1024} KB")
        }
        val items = arrayOf(
            "ⓘ Status database / diagnostics",
            "Setup/Start LocalDevDB (MySQL compatible)",
            "Stop LocalDevDB",
            "Start Native MariaDB",
            "Setup Native MariaDB + Import database.sql",
            "Copy config localdev",
            "Database diagnostics lengkap",
            "Reset Native DB data",
            "Compatibility DB: Import SQL",
            "Compatibility DB: Browse tables"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("Database • v1.1.2")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showTextDialog("Database status", message)
                    1 -> importedProject?.let { setupMysqlCompatThenRun(it, info.sqlFiles.firstOrNull(), showInline = false) }
                    2 -> {
                        mysqlCompat.stop()
                        status.text = "LocalDevDB stopped"
                    }
                    3 -> startNativeDbOnly()
                    4 -> setupNativeDbFromMenu(root, info.sqlFiles)
                    5 -> copyNativeDatabaseConfig()
                    6 -> showNativeDbDiagnostics()
                    7 -> confirmResetNativeDatabase()
                    8 -> chooseSqlForImport(info.sqlFiles)
                    9 -> showDatabaseTables(root)
                }
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun startNativeDbOnly() {
        status.text = "Starting MariaDB…"
        io.execute {
            runCatching { nativeDb.start { line -> appendConsole(line) } }
                .onSuccess {
                    runOnUiThread {
                        status.text = "MariaDB • 127.0.0.1:3306 • READY"
                        appendConsole("Native MariaDB READY • 127.0.0.1:3306")
                    }
                }
                .onFailure { t -> runOnUiThread {
                    status.text = "MariaDB gagal start"
                    showTextDialog("MariaDB gagal start", t.message ?: t.javaClass.simpleName)
                } }
        }
    }

    private fun setupNativeDbFromMenu(root: File, files: List<File>) {
        val sql = files.firstOrNull()
        MaterialAlertDialogBuilder(this)
            .setTitle("Setup localdev database")
            .setMessage(
                "MariaDB akan memakai 127.0.0.1:3306. Database/user/password default: localdev. " +
                    if (sql != null) "${sql.name} akan di-import." else "Tidak ada file .sql; hanya database localdev yang dibuat."
            )
            .setPositiveButton("Setup") { _, _ ->
                status.text = "Setup MariaDB…"
                io.execute {
                    runCatching { nativeDb.setupDefaultDatabase(sql) { line -> appendConsole(line) } }
                        .onSuccess { report -> runOnUiThread {
                            status.text = "MariaDB • localdev • READY"
                            appendConsole(report.message)
                            showTextDialog(
                                "Database siap",
                                "Host: localhost\nPort: 3306\nDatabase: localdev\nUser: localdev\nPassword: localdev\nCharset: utf8mb4\n\n${report.message}"
                            )
                        } }
                        .onFailure { t -> runOnUiThread {
                            status.text = "Setup Native DB gagal"
                            appendConsole("NATIVE DB ERROR: ${t.message ?: t.javaClass.simpleName}")
                            showTextDialog("Setup Native DB gagal", t.message ?: t.javaClass.simpleName)
                        } }
                }
            }
            .setNeutralButton("Copy Config") { _, _ -> copyNativeDatabaseConfig() }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showNativeDbDiagnostics() {
        val d = nativeDb.diagnostics()
        val compat = mysqlCompat.diagnostics()
        val php = PhpRuntimeManager(this).diagnostics(timeoutSeconds = 4)
        showTextDialog(
            "Database diagnostics",
            "LocalDevDB running: ${mark(compat.running)}\n" +
                "LocalDevDB port 3306: ${mark(compat.portOpen)}\n" +
                "LocalDevDB clients: ${compat.clients}\n" +
                "LocalDevDB queries: ${compat.queries}\n" +
                (if (compat.lastError.isBlank()) "" else "LocalDevDB last error: ${compat.lastError}\n") +
                "\nNative MariaDB server bundled: ${mark(d.serverBundled)}\n" +
                "Native MariaDB client bundled: ${mark(d.clientBundled)}\n" +
                "Seed datadir bundled: ${mark(d.seedBundled)}\n" +
                "Native MariaDB port 3306: ${mark(d.portOpen)}\n" +
                "PDO MySQL: ${mark(php.pdoMysql)}\n" +
                "mysqli: ${mark(php.mysqli)}\n\n" +
                "Native socket: ${d.socketPath}\n\n" +
                (if (d.logTail.isBlank()) "MariaDB log kosong." else d.logTail)
        )
    }

    private fun confirmResetNativeDatabase() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Reset Native Database?")
            .setMessage("Semua database MariaDB lokal LocalDev akan dihapus. File database.sql di project tidak dihapus.")
            .setPositiveButton("Reset") { _, _ ->
                io.execute {
                    runCatching { nativeDb.resetData() }
                    runOnUiThread {
                        status.text = "Native DB direset"
                        appendConsole("MariaDB local data reset")
                    }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun chooseSqlForImport(files: List<File>) {
        val root = importedProject?.root ?: return
        if (files.isEmpty()) {
            toast("database.sql atau file .sql tidak ditemukan di project.")
            return
        }
        if (files.size == 1) {
            importSqlDatabase(root, files.first())
            return
        }
        val labels = files.take(100).map { it.relativeTo(root).invariantSeparatorsPath }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Pilih file SQL")
            .setItems(labels) { _, which -> importSqlDatabase(root, files[which]) }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun importSqlDatabase(root: File, sqlFile: File) {
        status.text = "Import SQL…"
        appendConsole("SQL Lab: import ${sqlFile.name} → SQLite")
        io.execute {
            runCatching { sqlManager.importSqlToSqlite(root, sqlFile) }
                .onSuccess { report ->
                    runOnUiThread {
                        status.text = "SQL database siap • ${report.tableCount} tables"
                        appendConsole(
                            "SQL import selesai: total=${report.totalStatements} executed=${report.executed} " +
                                "skipped=${report.skipped} failed=${report.failed} tables=${report.tableCount}"
                        )
                        if (report.errors.isNotEmpty()) appendConsole(report.errors.joinToString("\n"))
                        showTextDialog(
                            "SQL import report",
                            "Source: ${report.sourceFile.name}\nDatabase: ${report.databaseFile.absolutePath}\n\n" +
                                "Statements: ${report.totalStatements}\nExecuted: ${report.executed}\nSkipped: ${report.skipped}\nFailed: ${report.failed}\nTables: ${report.tableCount}" +
                                if (report.errors.isEmpty()) "\n\nImport selesai tanpa error statement." else "\n\n${report.errors.joinToString("\n")}" 
                        )
                    }
                }
                .onFailure { t ->
                    runOnUiThread {
                        status.text = "SQL import gagal"
                        appendConsole("SQL ERROR: ${t.message ?: t.javaClass.simpleName}")
                        toast("Import SQL gagal: ${t.message}")
                    }
                }
        }
    }

    private fun showDatabaseTables(root: File) {
        val database = sqlManager.databaseFile(root)
        if (!database.isFile) {
            toast("Import database.sql terlebih dahulu.")
            return
        }
        io.execute {
            val tables = runCatching { sqlManager.tables(database) }.getOrElse {
                runOnUiThread { toast("Gagal membaca database: ${it.message}") }
                return@execute
            }
            runOnUiThread {
                if (tables.isEmpty()) {
                    showTextDialog("Database tables", "Database belum memiliki tabel.")
                } else {
                    val labels = tables.map { "${it.name}   (${if (it.rows >= 0) it.rows else "?"} rows)" }.toTypedArray()
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Tables • ${tables.size}")
                        .setItems(labels) { _, which ->
                            val table = tables[which].name.replace("`", "``")
                            runSqlAndShow(root, "SELECT * FROM `$table` LIMIT 100")
                        }
                        .setNegativeButton("Tutup", null)
                        .show()
                }
            }
        }
    }

    private fun showSqlQueryDialog(root: File) {
        val database = sqlManager.databaseFile(root)
        if (!database.isFile) {
            toast("Import database.sql terlebih dahulu.")
            return
        }
        val input = EditText(this).apply {
            setText("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name;")
            setSelection(text.length)
            setTextColor(color(R.color.localdev_text))
            setHintTextColor(color(R.color.localdev_muted))
            typeface = Typeface.MONOSPACE
            minLines = 5
            maxLines = 12
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("SQL Query")
            .setView(input)
            .setPositiveButton("Run") { _, _ -> runSqlAndShow(root, input.text.toString()) }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun runSqlAndShow(root: File, sql: String) {
        val database = sqlManager.databaseFile(root)
        io.execute {
            runCatching { sqlManager.query(database, sql) }
                .onSuccess { result -> runOnUiThread { showTextDialog("SQL result", result.text) } }
                .onFailure { t -> runOnUiThread { showTextDialog("SQL error", t.message ?: t.javaClass.simpleName) } }
        }
    }

    private fun generateSqlAdapter(root: File) {
        val adapter = runCatching { sqlManager.generatePhpAdapter(root) }.getOrElse {
            toast("Gagal membuat adapter: ${it.message}")
            return
        }
        appendConsole("PHP SQLite adapter: ${adapter.absolutePath}")
        showTextDialog(
            "PHP SQLite adapter",
            "Adapter dibuat di:\n${adapter.absolutePath}\n\nGunakan localdev_pdo() untuk mendapatkan koneksi PDO SQLite. " +
                "Path database juga tersedia lewat environment LOCALDEV_SQLITE_PATH saat PHP dijalankan."
        )
    }

    private fun confirmPdoPatch(root: File) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Patch PDO config?")
            .setMessage(
                "LocalDev akan mencoba mengganti pola DSN PDO MySQL sederhana menjadi SQLite untuk testing lokal. " +
                    "Backup dibuat sebelum file diubah. Query MySQL-specific tetap bisa membutuhkan penyesuaian."
            )
            .setPositiveButton("Patch + Backup") { _, _ ->
                io.execute {
                    runCatching { sqlManager.patchCommonPdoConfig(root) }
                        .onSuccess { report ->
                            runOnUiThread {
                                appendConsole("PDO patch: ${report.changedFiles.size} file • backup=${report.backupRoot.absolutePath}")
                                val changed = if (report.changedFiles.isEmpty()) "Tidak ada file yang dipatch." else report.changedFiles.joinToString("\n") { it.relativeTo(root).invariantSeparatorsPath }
                                showTextDialog(
                                    "PDO compatibility patch",
                                    "$changed\n\nBackup:\n${report.backupRoot.absolutePath}\n\n${report.warnings.joinToString("\n")}".trim()
                                )
                                currentFile?.let { if (it.isFile) loadProjectFile(it, openKeyboard = false) }
                            }
                        }
                        .onFailure { t -> runOnUiThread { toast("Patch gagal: ${t.message}") } }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun confirmResetDatabase(root: File) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Reset Local SQLite?")
            .setMessage("Database testing .localdev/localdev.sqlite akan dihapus. database.sql asli tidak dihapus.")
            .setPositiveButton("Hapus") { _, _ ->
                val file = sqlManager.databaseFile(root)
                val ok = !file.exists() || file.delete()
                status.text = if (ok) "Local SQLite direset" else "Reset database gagal"
                toast(if (ok) "Local database dihapus" else "Database tidak dapat dihapus")
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showTextDialog(title: String, message: String) {
        val text = TextView(this).apply {
            this.text = message
            setTextColor(color(R.color.localdev_text))
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        val scroll = ScrollView(this).apply { addView(text) }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("Tutup", null)
            .show()
    }

    private fun showMoreMenu() {
        val items = arrayOf(
            "Database",
            "Restore production config",
            "Run PHP",
            "Stop PHP + DB",
            "PHP Info",
            "Import file code",
            "Reload preview",
            "Clear console"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("LocalDev tools")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showDatabaseMenu()
                    1 -> {
                        val root = importedProject?.root
                        if (root == null) {
                            toast("Belum ada project yang di-import")
                        } else {
                            val restored = sqlManager.restoreLatestMysqlTcpProfile(root)
                            if (restored > 0) {
                                appendConsole("Production config restored: $restored file")
                                status.text = "Production config restored"
                                toast("$restored config dikembalikan")
                            } else {
                                toast("Backup LocalDev config belum ada")
                            }
                        }
                    }
                    2 -> runImportedPhp(showInline = true)
                    3 -> { server.stop(); nativeDb.stop(); mysqlCompat.stop(); status.text = "Local services stopped" }
                    4 -> checkRuntime(showDetails = true)
                    5 -> openCodeFile.launch(arrayOf("text/*", "application/javascript", "application/json", "application/x-httpd-php", "application/sql"))
                    6 -> refreshAllVisiblePreviews()
                    7 -> console.text = ""
                }
            }
            .show()
    }

    private fun checkRuntime(showDetails: Boolean) {
        io.execute {
            val d = PhpRuntimeManager(this).diagnostics()
            runOnUiThread {
                if (!d.available) {
                    status.text = "PHP runtime belum tersedia • HTML/CSS/JS tetap aktif"
                    if (showDetails) appendConsole("PHP: ${d.error}\n${d.raw}")
                    return@runOnUiThread
                }
                val sessionText = if (d.session) "native" else "LocalDev polyfill"
                val ext = "PDO_SQLite=${mark(d.pdoSqlite)} SQLite3=${mark(d.sqlite3)} PDO_MySQL=${mark(d.pdoMysql)} mysqli=${mark(d.mysqli)} cURL=${mark(d.curl)} OpenSSL=${mark(d.openssl)} ZIP=${mark(d.zip)} Session=$sessionText"
                status.text = if (d.allTargetExtensionsReady) "PHP ${d.version} ARM64 • READY" else "PHP ${d.version} • extension belum lengkap"
                if (showDetails) appendConsole("PHP ${d.version} (${d.phpVersionId})\n$ext")
            }
        }
    }

    private fun mark(ok: Boolean) = if (ok) "OK" else "MISS"

    private fun updateTabStyles() {
        tabButtons.forEach { (name, button) ->
            val active = name == currentTab
            button.backgroundTintList = ColorStateList.valueOf(if (active) color(R.color.localdev_cyan) else color(R.color.localdev_surface_2))
            button.setTextColor(if (active) color(R.color.localdev_bg) else color(R.color.localdev_muted))
            button.strokeWidth = dp(1)
            button.strokeColor = ColorStateList.valueOf(if (active) color(R.color.localdev_cyan) else color(R.color.localdev_outline))
        }
    }

    private fun resetEditorViewport(keepCursor: Boolean) {
        if (!keepCursor && editor.text != null) editor.setSelection(0)
        editor.post {
            editor.scrollTo(0, 0)
            if (keepCursor) {
                val cursor = editor.selectionStart.coerceAtLeast(0)
                editor.setSelection(cursor.coerceAtMost(editor.text.length))
            }
        }
    }

    private fun appendConsole(message: String) {
        runOnUiThread {
            val old = console.text?.toString().orEmpty()
            console.text = (if (old.isBlank()) message else "$old\n$message").takeLast(9000)
            consoleScroll.post { consoleScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun applySafeArea(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            // System bars are immersive-hidden. Keep only enough padding for a physical
            // camera cutout/notch so content never sits underneath the hole-punch.
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val left = maxOf(dp(12), cutout.left + dp(6))
            val top = maxOf(dp(8), cutout.top + dp(4))
            val right = maxOf(dp(12), cutout.right + dp(6))
            val bottom = maxOf(dp(8), cutout.bottom + dp(4))
            target.setPadding(left, top, right, bottom)

            val nowImeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (nowImeVisible != imeVisible) {
                imeVisible = nowImeVisible
                headerView.visibility = if (nowImeVisible) View.GONE else View.VISIBLE
                statusCard.visibility = if (nowImeVisible) View.GONE else View.VISIBLE
                consoleCard.visibility = if (nowImeVisible) View.GONE else View.VISIBLE
                actionsPanel.visibility = if (nowImeVisible) View.GONE else View.VISIBLE
                tabsView.visibility = View.VISIBLE

                // Some OEM keyboards temporarily restore navigation controls. Re-hide
                // them as soon as the IME visibility transition settles.
                target.postDelayed({ enterImmersiveMode() }, 120L)
            }
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }

    private fun enterImmersiveMode() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    override fun onResume() {
        super.onResume()
        window.decorView.post { enterImmersiveMode() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.postDelayed({ enterImmersiveMode() }, 80L)
        }
    }

    private fun installBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    fullscreenContainer.visibility == View.VISIBLE -> exitFullscreenPreview()
                    imeVisible -> WindowInsetsControllerCompat(window, editor).hide(WindowInsetsCompat.Type.ime())
                    floatingCard.visibility == View.VISIBLE -> hideFloatingPreview()
                    preview.visibility == View.VISIBLE -> showEditor(openKeyboard = false)
                    else -> finish()
                }
            }
        })
    }

    private fun queryDisplayName(uri: Uri): String {
        var result = ""
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) result = cursor.getString(index).orEmpty()
            }
        }
        return result
    }

    private fun roundedDrawable(fill: Int, radius: Int, stroke: Int) = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun color(res: Int): Int = ContextCompat.getColor(this, res)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        uiHandler.removeCallbacks(liveRefresh)
        saveCurrentEditor(showMessage = false)
        server.close()
        if (::nativeDb.isInitialized) nativeDb.stop()
        if (::mysqlCompat.isInitialized) mysqlCompat.stop()
        io.shutdownNow()
        runCatching { preview.destroy() }
        runCatching { fullscreenPreview.destroy() }
        runCatching { floatingPreview.destroy() }
        super.onDestroy()
    }
}
