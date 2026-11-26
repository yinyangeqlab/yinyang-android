package com.superglobalpeq.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.superglobalpeq.app.dsp.EqualizerApoParser
import com.superglobalpeq.app.model.EqPreset
import rikka.shizuku.Shizuku
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors

class PlaybackActivity : Activity() {
    private enum class Setup { MISSING, STOPPED, UNAUTHORIZED, UNSUPPORTED, READY }
    private enum class Screen { HOME, LIBRARY, REVIEW }

    private val handler = Handler(Looper.getMainLooper())
    private val reader = Executors.newSingleThreadExecutor()
    private val appPackage by lazy {
        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    }
    private lateinit var appearance: PhoneAppearance
    private lateinit var library: PresetLibrary
    private lateinit var home: ScrollView
    private lateinit var libraryPanel: FrameLayout
    private lateinit var homeButton: Button
    private lateinit var libraryButton: Button
    private lateinit var companionButton: Button
    private lateinit var rows: PresetRows
    private lateinit var presetList: ListView
    private lateinit var libraryEmpty: TextView
    private lateinit var kindButton: Button
    private lateinit var backupButton: Button
    private lateinit var query: EditText
    private lateinit var reviewName: EditText
    private lateinit var openTxt: Button
    private lateinit var setupPanel: LinearLayout
    private lateinit var setupHeading: TextView
    private lateinit var setupHint: TextView
    private lateinit var setupAction: Button
    private lateinit var installLinks: LinearLayout
    private lateinit var presetPanel: LinearLayout
    private lateinit var presetHeading: TextView
    private lateinit var presetName: TextView
    private lateinit var presetHint: TextView
    private lateinit var fitDetails: TextView
    private lateinit var filters: LinearLayout
    private lateinit var filterCounts: List<TextView>
    private lateinit var filterDetails: TextView
    private lateinit var toggleFilters: Button
    private lateinit var playbackModes: LinearLayout
    private lateinit var modeButtons: Map<PresetPlaybackMode, Button>
    private lateinit var exportActions: LinearLayout
    private lateinit var reviewForm: LinearLayout
    private lateinit var reviewActions: LinearLayout
    private lateinit var importNote: TextView
    private lateinit var footer: LinearLayout
    private lateinit var playbackStatus: TextView
    private lateinit var playback: Button
    private lateinit var stop: Button
    private var bugDialog: AlertDialog? = null
    private var restoreOffer: AlertDialog? = null
    private var themeIndex = 0
    private var setup: Setup? = null
    private var selected: EqPreset? = null
    private var saved = emptyList<EqPreset>()
    private var screen = Screen.HOME
    private var reviewParent = Screen.HOME
    private var kind: PresetKind? = null
    private var companionReturn = false
    private var keyboardVisible = false
    private var backRegistered = false
    private var playingPresetId: String? = null
    private var incoming: EqPreset? = null
    private var skippedLines = 0
    private var showFilters = false
    private var renderedDetails: EqPreset? = null
    private var importMessage = ""
    private var pendingExport: String? = null
    private var pairedMode = PresetPlaybackMode.BOTH
    private var request = 0
    private val back = OnBackInvokedCallback { backToParent() }
    private val updateSavedPresets = Runnable { reloadLibrary(); refresh() }
    private val libraryListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (PresetLibrary.ownsKey(key)) {
            handler.removeCallbacks(updateSavedPresets)
            handler.post(updateSavedPresets)
        }
    }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { code, _ ->
        if (code == SHIZUKU_REQUEST) handler.post { setup = null; refresh() }
    }
    private val refreshLoop = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val savedTheme = getPreferences(MODE_PRIVATE).getInt("theme", 0)
        themeIndex = if (savedTheme in PhoneAppearance.names.indices) savedTheme else 0
        if (themeIndex == 2) setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        appearance = PhoneAppearance.at(themeIndex)
        library = PresetLibrary(getPreferences(MODE_PRIVATE))
        selected = library.selected()
        saved = library.load()
        incoming = PresetLibrary.decode(savedInstanceState?.getString("incoming"))
        screen = Screen.entries.getOrElse(savedInstanceState?.getInt("screen") ?: 0) { Screen.HOME }
        if (screen == Screen.REVIEW && incoming == null) screen = Screen.HOME
        reviewParent = if (savedInstanceState?.getBoolean("reviewFromLibrary") == true) Screen.LIBRARY else Screen.HOME
        kind = PresetKind.entries.getOrNull(savedInstanceState?.getInt("kind", -1) ?: -1)
        companionReturn = savedInstanceState?.getBoolean("companionReturn") ?: fromCompanion(intent)
        skippedLines = savedInstanceState?.getInt("skipped") ?: 0
        showFilters = savedInstanceState?.getBoolean("showFilters") ?: false
        importMessage = savedInstanceState?.getString("importMessage").orEmpty()
        pendingExport = savedInstanceState?.getString("pendingExport")
        val modeName = savedInstanceState?.getString("pairedMode")
        pairedMode = PresetPlaybackMode.entries.firstOrNull { it.name == modeName }
            ?: PlaybackService.status.takeIf { it.activePresetId == selected?.id }?.activeMode
            ?: PresetPlaybackMode.BOTH
        createContent()
        reviewName.setText(savedInstanceState?.getString("reviewName") ?: incoming?.name.orEmpty())
        query.setText(savedInstanceState?.getString("query").orEmpty())
        updateLibrary()
        Shizuku.addRequestPermissionResultListener(permissionListener)
        if (savedInstanceState == null) receive(intent)
        refresh()
        val scroll = savedInstanceState?.getInt("scroll") ?: 0
        home.post { home.scrollTo(0, scroll) }
        presetList.setSelectionFromTop(savedInstanceState?.getInt("listPosition") ?: 0,
            savedInstanceState?.getInt("listOffset") ?: 0)
        if (savedInstanceState?.getBoolean("bugReportOpen") == true) showBugReport()
        offerLibraryRestore()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        incoming?.let { out.putString("incoming", it.toJson().toString()) }
        out.putInt("skipped", skippedLines)
        out.putBoolean("showFilters", showFilters)
        out.putInt("screen", screen.ordinal)
        out.putBoolean("reviewFromLibrary", reviewParent == Screen.LIBRARY)
        out.putBoolean("companionReturn", companionReturn)
        out.putInt("kind", kind?.ordinal ?: -1)
        out.putString("query", query.text.toString())
        out.putString("reviewName", reviewName.text.toString())
        out.putString("importMessage", importMessage)
        out.putString("pendingExport", pendingExport)
        out.putString("pairedMode", pairedMode.name)
        out.putInt("scroll", home.scrollY)
        out.putInt("listPosition", presetList.firstVisiblePosition)
        out.putInt("listOffset", presetList.getChildAt(0)?.top ?: 0)
        out.putBoolean("bugReportOpen", bugDialog?.isShowing == true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_SEND) restoreOffer?.dismiss()
        receive(intent)
        refresh()
    }

    // Gestures use OnBackInvokedCallback. This also handles legacy Back key dispatch.
    @SuppressLint("GestureBackNavigation")
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (!backToParent()) super.onBackPressed()
    }

    private fun backToParent(): Boolean {
        if (screen == Screen.HOME) return false
        navigate(if (screen == Screen.REVIEW) reviewParent else Screen.HOME)
        return true
    }

    override fun onResume() {
        super.onResume()
        getPreferences(MODE_PRIVATE).registerOnSharedPreferenceChangeListener(libraryListener)
        reloadLibrary()
        handler.post(refreshLoop)
    }

    private fun reloadLibrary() {
        val latestSelection = library.selected()
        val latestLibrary = library.load()
        if (latestSelection != selected || latestLibrary != saved) {
            if (latestSelection?.id != selected?.id) pairedMode = PresetPlaybackMode.BOTH
            selected = latestSelection
            saved = latestLibrary
            updateLibrary()
        }
    }

    override fun onPause() {
        getPreferences(MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(libraryListener)
        handler.removeCallbacks(updateSavedPresets)
        handler.removeCallbacks(refreshLoop)
        super.onPause()
    }

    override fun onDestroy() {
        restoreOffer?.dismiss()
        bugDialog?.dismiss()
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        if (backRegistered) onBackInvokedDispatcher.unregisterOnBackInvokedCallback(back)
        reader.shutdown()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun createContent() {
        val root = column().apply {
            setBackgroundColor(appearance.background)
            isFocusableInTouchMode = true
        }
        val bars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        root.setOnApplyWindowInsetsListener { view, insets ->
            val area = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(area.left, area.top, area.right,
                maxOf(area.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom))
            keyboardVisible = insets.isVisible(WindowInsets.Type.ime())
            refreshFooter(PlaybackService.status, setup == Setup.READY)
            insets
        }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(16), dp(4), dp(16), dp(4))
        }
        val width = resources.configuration.screenWidthDp
        if (width >= 400) {
            header.addView(ImageView(this).apply {
                setImageResource(R.drawable.brand_header)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, dp(52), dp(36))
        }
        header.addView(column().apply {
            setPaddingRelative(dp(if (width >= 400) 10 else 0), 0, dp(8), 0)
            addView(label("YinYang Phone EQ", 21, appearance.ink, true).apply {
                isAccessibilityHeading = true
            }, -1, -2)
            addView(label("v${appPackage.versionName} · Build ${appPackage.longVersionCode}",
                13, appearance.muted), -1, -2)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("Report a bug", action = ::showBugReport).apply {
            maxWidth = dp(170)
            setPaddingRelative(dp(10), dp(8), dp(10), dp(8))
            setButtonIcon(R.drawable.ic_bug)
        }, -2, -2)
        root.addView(header, -1, -2)
        root.addView(divider())

        val navigation = FrameLayout(this)
        val navItems = LinearLayout(this).apply {
            isBaselineAligned = false
            setPaddingRelative(dp(16), dp(8), dp(16), dp(8))
            homeButton = navButton("Home") { navigate(Screen.HOME) }
            libraryButton = navButton("Library") { navigate(Screen.LIBRARY) }
            companionButton = navButton("Back to YYEQ5K") { finish() }
            addView(homeButton, LinearLayout.LayoutParams(0, -1, 1f))
            addView(libraryButton, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(6) })
            addView(companionButton, LinearLayout.LayoutParams(0, -1, 1.55f).apply { marginStart = dp(6) })
            addView(button(if (width >= 480) "Theme" else "", action = ::chooseTheme).apply {
                contentDescription = "Theme"
                tooltipText = "Theme"
                setButtonIcon(R.drawable.ic_theme)
            }, LinearLayout.LayoutParams(if (width >= 480) -2 else dp(48), -1).apply { marginStart = dp(8) })
        }
        navigation.addView(navItems, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL))
        limitWidth(navigation, navItems)
        root.addView(navigation, -1, -2)

        val body = FrameLayout(this)
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        home = ScrollView(this).apply { clipToPadding = false }
        body.addView(home, -1, -1)
        val content = column().apply { setPaddingRelative(dp(16), dp(18), dp(16), dp(22)) }
        home.addView(content, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL))
        limitWidth(home, content)
        content.addView(buildPresets(), -1, -2)
        content.addView(buildSetup(), spaced(14))
        importNote = label("", 14, appearance.warning).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(importNote, spaced(14))
        content.addView(label(
            "Phone EQ uses the Quve audio engine by nanbeimerco to apply system-wide EQ to media and games on this phone.",
            14, appearance.muted
        ), spaced(18))
        content.addView(button("View Quve source") {
            openLink("https://github.com/nanbeimerco/Quve/tree/v1.0.2")
        }, spaced(8, -2))
        libraryPanel = buildLibrary()
        body.addView(libraryPanel, -1, -1)

        val bottom = FrameLayout(this)
        footer = column().apply { setPaddingRelative(dp(16), dp(10), dp(16), dp(12)) }
        bottom.addView(footer, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL))
        limitWidth(bottom, footer)
        footer.addView(divider())
        playbackStatus = label("", 14, appearance.muted).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        footer.addView(playbackStatus, spaced(9))
        reviewActions = LinearLayout(this).apply {
            isBaselineAligned = false
            addView(navButton("Cancel review") { navigate(reviewParent) },
                LinearLayout.LayoutParams(0, -1, 1f))
            addView(navButton("Save & select", prominent = true, action = ::acceptPreset),
                LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(8) })
        }
        footer.addView(reviewActions, spaced(8))
        stop = button("Stop phone EQ", prominent = true) {
            startService(Intent(this, PlaybackService::class.java).setAction(PlaybackService.STOP))
        }
        playback = button("Start phone EQ", prominent = true, action = ::startPlayback)
        footer.addView(stop, spaced(8))
        footer.addView(playback, spaced(8))
        root.addView(bottom, -1, -2)
        setContentView(root)
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = appearance.background
        window.navigationBarColor = appearance.background
        root.post { root.windowInsetsController?.setSystemBarsAppearance(if (themeIndex == 2) bars else 0, bars) }
        root.requestApplyInsets()
    }

    private fun buildSetup(): LinearLayout = panel().apply {
        addView(label("Set up Shizuku", 25, appearance.ink, true).apply { isAccessibilityHeading = true })
        setupHeading = label("", 17, appearance.accent, true).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        addView(setupHeading, spaced(14))
        setupHint = label("", 15, appearance.muted)
        addView(setupHint, spaced(5))
        setupAction = button("Open Shizuku", prominent = true, action = ::openShizuku)
        addView(setupAction, spaced(14))
        installLinks = column().apply {
            addView(button("Shizuku on Google Play", prominent = true) {
                openLink("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api")
            }, -1, -2)
            addView(button("Shizuku on GitHub") {
                openLink("https://github.com/RikkaApps/Shizuku/releases")
            }, spaced(8))
        }
        addView(installLinks, spaced(14))
        addView(button("Shizuku setup guide") { openLink("https://shizuku.rikka.app/guide/setup/") }, spaced(8))
    }.also { setupPanel = it }

    private fun buildPresets(): LinearLayout = column().apply {
        presetHeading = label("Playback", 25, appearance.ink, true).apply { isAccessibilityHeading = true }
        addView(presetHeading, -1, -2)
        addView(panel().apply {
            presetName = label("", 20, appearance.ink, true)
            addView(presetName, -1, -2)
            reviewForm = column().apply {
                reviewName = textInput("Preset name", 100, EditorInfo.IME_ACTION_DONE).apply {
                    id = View.generateViewId()
                }
                addView(label("Preset name", 14, appearance.muted).apply { labelFor = reviewName.id })
                addView(reviewName, spaced(5))
            }
            addView(reviewForm, -1, -2)
            presetHint = label("", 15, appearance.muted)
            addView(presetHint, spaced(6))
            fitDetails = label("", 14, appearance.muted)
            addView(fitDetails, spaced(10))
            filters = LinearLayout(this@PlaybackActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setBaselineAligned(false)
            }
            filterCounts = listOf("Main", "Left", "Right").map { name ->
                val unit = column()
                val count = label("", 24, appearance.ink, true)
                unit.addView(count, -1, -2)
                unit.addView(label(name, 14, appearance.muted), -1, -2)
                filters.addView(unit, LinearLayout.LayoutParams(0, -2, 1f))
                count
            }
            addView(filters, spaced(15))
            playbackModes = column().apply {
                addView(label("Playback mode", 14, appearance.muted))
                val modeRow = LinearLayout(this@PlaybackActivity).apply { isBaselineAligned = false }
                modeButtons = PresetPlaybackMode.entries.associateWith { mode ->
                    navButton(mode.label) {
                        pairedMode = mode
                        refresh()
                    }.also { control ->
                        modeRow.addView(control, LinearLayout.LayoutParams(0, -1, 1f).apply {
                            if (mode != PresetPlaybackMode.MAIN) marginStart = dp(6)
                        })
                    }
                }
                addView(modeRow, spaced(6))
            }
            addView(playbackModes, spaced(16))
            toggleFilters = button("Show filters") {
                showFilters = !showFilters
                refresh()
            }
            addView(toggleFilters, spaced(12))
            filterDetails = label("", 14, appearance.ink)
            addView(filterDetails, spaced(10))
            exportActions = LinearLayout(this@PlaybackActivity).apply {
                isBaselineAligned = false
                addView(navButton("Copy filters") {
                    selected?.let { copyToClipboard("Phone EQ filters", PresetInput.export(it)) }
                }, LinearLayout.LayoutParams(0, -1, 1f))
                addView(navButton("Export TXT", action = ::exportPreset),
                    LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(8) })
            }
            addView(exportActions, spaced(8))
        }, spaced(12))
        openTxt = button("Import preset TXT", action = ::openTextFile)
        addView(openTxt, spaced(12))
    }.also { presetPanel = it }

    private fun buildLibrary(): FrameLayout = FrameLayout(this).apply {
        val content = column().apply {
            setPaddingRelative(dp(16), dp(8), dp(16), dp(10))
            addView(label("Preset library", 25, appearance.ink, true).apply { isAccessibilityHeading = true })
            query = textInput("Search presets", 100, EditorInfo.IME_ACTION_SEARCH)
            query.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updateLibrary(resetScroll = true)
                override fun afterTextChanged(s: Editable?) = Unit
            })
            addView(query, spaced(10))
            val tools = LinearLayout(this@PlaybackActivity).apply {
                isBaselineAligned = false
                kindButton = button("All presets", action = ::chooseKind)
                addView(kindButton, LinearLayout.LayoutParams(0, -1, 1f))
                addView(button("Import TXT", action = ::openTextFile),
                    LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(8) })
            }
            addView(tools, spaced(8))
            val backupTools = LinearLayout(this@PlaybackActivity).apply {
                isBaselineAligned = false
                backupButton = button("Back up library", action = ::chooseLibraryBackupDestination)
                addView(backupButton, LinearLayout.LayoutParams(0, -1, 1f))
                addView(button("Restore library", action = ::chooseLibraryBackup),
                    LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(8) })
            }
            addView(backupTools, spaced(8))
            val results = FrameLayout(this@PlaybackActivity)
            addView(results, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
            rows = PresetRows()
            presetList = ListView(this@PlaybackActivity).apply {
                adapter = rows
                divider = android.graphics.drawable.ColorDrawable(appearance.line)
                dividerHeight = dp(1)
                setOnItemClickListener { _, _, position, _ ->
                    val preset = rows.getItem(position)
                    library.select(preset)
                    if (selected?.id != preset.id) pairedMode = PresetPlaybackMode.BOTH
                    selected = preset
                    showFilters = false
                    updateLibrary()
                    navigate(Screen.HOME)
                    home.scrollTo(0, 0)
                }
            }
            results.addView(presetList, -1, -1)
            libraryEmpty = label("", 15, appearance.muted).apply {
                setPaddingRelative(0, dp(16), 0, dp(16))
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            results.addView(libraryEmpty, -1, -2)
            presetList.emptyView = libraryEmpty
        }
        addView(content, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL))
        limitWidth(this, content)
    }

    private inner class PresetRows : BaseAdapter() {
        var items = emptyList<EqPreset>()
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): EqPreset = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as? LinearLayout ?: column().apply {
                setPaddingRelative(dp(10), dp(10), dp(10), dp(10))
                minimumHeight = dp(56)
                addView(label("", 16, appearance.ink, true))
                addView(label("", 14, appearance.muted))
            }
            val preset = getItem(position)
            val isSelected = preset.id == selected?.id
            val state = when (preset.id) {
                playingPresetId -> " • Playing"
                selected?.id -> " • Selected"
                else -> ""
            }
            row.setBackgroundColor(if (isSelected) appearance.active else android.graphics.Color.TRANSPARENT)
            row.isActivated = isSelected
            (row.getChildAt(0) as TextView).text = preset.name
            (row.getChildAt(1) as TextView).text = PresetKind.of(preset).label + state
            return row
        }
    }

    private fun textInput(hintText: String, maxLength: Int, action: Int) = EditText(this).apply {
        hint = hintText
        setSingleLine()
        inputType = InputType.TYPE_CLASS_TEXT
        imeOptions = action or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        filters = arrayOf(InputFilter.LengthFilter(maxLength))
        textSize = 16f
        minHeight = dp(48)
        setPaddingRelative(dp(12), dp(8), dp(12), dp(8))
        setTextColor(appearance.ink)
        setHintTextColor(appearance.muted)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), box(appearance.panel, appearance.accent, 10, 2))
            addState(intArrayOf(), box(appearance.panel, appearance.line, 10))
        }
        setOnEditorActionListener { _, id, _ ->
            if (id == action) hideKeyboard()
            id == action
        }
    }

    private fun chooseKind() {
        val kinds = listOf<PresetKind?>(null) + PresetKind.entries
        AlertDialog.Builder(this).setTitle("Filter presets")
            .setSingleChoiceItems(kinds.map { it?.label ?: "All presets" }.toTypedArray(), kinds.indexOf(kind)) { dialog, index ->
                kind = kinds[index]
                updateLibrary(resetScroll = true)
                dialog.dismiss()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun updateLibrary(resetScroll: Boolean = false) {
        val terms = query.text.toString().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val chosen = kind
        rows.items = saved.filter { preset ->
            val type = PresetKind.of(preset)
            val fields = listOf(preset.name, type.label) + preset.fit?.let {
                listOf(it.device, it.tips, it.size, it.target, it.fitter)
            }.orEmpty()
            (chosen == null || chosen == type) && terms.all { term ->
                fields.any { it.contains(term, ignoreCase = true) }
            }
        }
        kindButton.update(chosen?.label ?: "All presets")
        backupButton.isEnabled = saved.isNotEmpty()
        kindButton.contentDescription = "Filter presets, ${kindButton.text}"
        libraryEmpty.update(if (saved.isEmpty()) "Import a TXT file or open a fit from YYEQ5K to save a preset."
            else "No matching presets.")
        rows.notifyDataSetChanged()
        if (resetScroll) presetList.setSelection(0)
    }

    private fun hideKeyboard() {
        currentFocus?.let { focused ->
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(focused.windowToken, 0)
            focused.clearFocus()
        }
    }

    private fun navigate(next: Screen) {
        if (screen == next) return
        hideKeyboard()
        if (screen == Screen.REVIEW) {
            incoming = null
            skippedLines = 0
            showFilters = false
        }
        screen = next
        refresh()
    }

    private fun openTextFile() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain"), OPEN_TXT)
        } catch (_: ActivityNotFoundException) {
            notifyUser("Could not open the Android file picker.")
        }
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun panel() = column().apply {
        background = box(appearance.panel, appearance.line, 14)
        setPaddingRelative(dp(16), dp(16), dp(16), dp(16))
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(appearance.line)
        layoutParams = LinearLayout.LayoutParams(-1, dp(1))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun limitWidth(parent: FrameLayout, child: View) {
        parent.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val width = minOf(view.width, dp(650))
            if (width > 0 && child.layoutParams.width != width) {
                child.layoutParams = child.layoutParams.apply { this.width = width }
            }
        }
    }

    private fun spaced(top: Int, width: Int = -1) =
        LinearLayout.LayoutParams(width, -2).apply { topMargin = dp(top) }

    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(value: String, prominent: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = value
        textSize = 15f
        isAllCaps = false
        minHeight = dp(48)
        minimumWidth = dp(48)
        setPaddingRelative(dp(14), dp(8), dp(14), dp(8))
        val color = if (prominent) appearance.accent else appearance.panel
        background = RippleDrawable(ColorStateList.valueOf(0x30799c9c), StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), box(appearance.active, appearance.line, 10))
            addState(intArrayOf(android.R.attr.state_focused), box(color, appearance.ink, 10, 2))
            addState(intArrayOf(android.R.attr.state_selected), box(appearance.active, appearance.accent, 10))
            addState(intArrayOf(), box(color, if (prominent) color else appearance.line, 10))
        }, null)
        setTextColor(ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(appearance.muted, if (prominent) appearance.background else appearance.ink)
        ))
        setOnClickListener { action() }
    }

    private fun navButton(value: String, prominent: Boolean = false, action: () -> Unit) =
        button(value, prominent, action).apply { setPaddingRelative(dp(6), dp(8), dp(6), dp(8)) }

    private fun Button.setButtonIcon(resource: Int) {
        val icon = getDrawable(resource)?.mutate()?.apply { setTint(appearance.ink) }
        compoundDrawablePadding = dp(6)
        setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
    }

    private fun box(fill: Int, stroke: Int, radius: Int, width: Int = 1) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(width), stroke)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    private fun chooseTheme() {
        AlertDialog.Builder(this).setTitle("Theme")
            .setSingleChoiceItems(PhoneAppearance.names, themeIndex) { dialog, index ->
                dialog.dismiss()
                if (index != themeIndex) {
                    getPreferences(MODE_PRIVATE).edit().putInt("theme", index).apply()
                    recreate()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun openLink(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: ActivityNotFoundException) { importMessage = "Open the link in a browser."; refresh() }
    }

    private fun showBugReport() {
        val app = appPackage
        val shizukuVersion = runCatching {
            packageManager.getPackageInfo(SHIZUKU_PACKAGE, PackageManager.PackageInfoFlags.of(0)).versionName
        }.getOrNull()
        val shizukuStatus = when (shizukuSetup()) {
            Setup.MISSING -> "not installed"
            Setup.STOPPED -> "not running"
            Setup.UNAUTHORIZED -> "running via debugging, access not granted"
            Setup.UNSUPPORTED -> "running in an unsupported mode"
            Setup.READY -> "running via debugging, access granted"
        }
        val outputs = getSystemService(AudioManager::class.java)
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { outputName(it.type) }.distinct().sorted()
        val report = buildString {
            appendLine("YinYang Phone EQ ${app.versionName} (build ${app.longVersionCode})")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Android build ${Build.DISPLAY}")
            appendLine("Android security patch ${Build.VERSION.SECURITY_PATCH}")
            appendLine("Shizuku: $shizukuStatus${shizukuVersion?.let { " (version $it)" }.orEmpty()}")
            val notifications = if (PlaybackService.notificationsEnabled(this@PlaybackActivity)) "enabled" else "disabled"
            appendLine("Playback notifications $notifications")
            appendLine("Available audio outputs: ${outputs.joinToString().ifEmpty { "none reported" }}")
            appendLine()
            append(PlaybackService.lastStartup(this@PlaybackActivity) ?: "No playback startup recorded yet.")
        }
        bugDialog = AlertDialog.Builder(this)
            .setTitle("Report a bug")
            .setMessage("Copy these details and share them with the person helping you.\n\n$report")
            .setNegativeButton("Close", null)
            .setPositiveButton("Copy details") { _, _ ->
                copyToClipboard("Phone EQ diagnostics", report)
            }.show().also { dialog -> dialog.setOnDismissListener { bugDialog = null } }
    }

    private fun copyToClipboard(label: String, text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun outputName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "Speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headphones"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB audio"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST -> "Bluetooth audio"
        AudioDeviceInfo.TYPE_HEARING_AID -> "Hearing aid"
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI"
        else -> "Android output type $type"
    }

    private fun shizukuSetup(): Setup {
        if (Shizuku.pingBinder()) return runCatching {
            if (Shizuku.getUid() != 2000) Setup.UNSUPPORTED
            else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) Setup.READY
            else Setup.UNAUTHORIZED
        }.getOrDefault(Setup.STOPPED)
        return if (packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE) != null) Setup.STOPPED else Setup.MISSING
    }

    private fun openShizuku() {
        if (shizukuSetup() == Setup.UNAUTHORIZED && !Shizuku.shouldShowRequestPermissionRationale()) {
            Shizuku.requestPermission(SHIZUKU_REQUEST)
            return
        }
        val launcher = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        if (launcher != null) startActivity(launcher) else openLink("https://shizuku.rikka.app/guide/setup/")
    }

    private fun renderSetup(next: Setup) {
        setup = next
        if (next == Setup.READY) return
        val manualGrant = next == Setup.UNAUTHORIZED &&
            runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)
        setupHeading.text = when (next) {
            Setup.MISSING -> "Shizuku is missing"
            Setup.STOPPED -> "Shizuku is stopped"
            Setup.UNAUTHORIZED -> "Approval needed"
            Setup.UNSUPPORTED -> "Start with Android debugging"
            Setup.READY -> ""
        }
        setupHint.text = when (next) {
            Setup.MISSING -> "Install Shizuku, then start it using Wireless debugging."
            Setup.STOPPED, Setup.UNSUPPORTED -> "Open Shizuku and start it using Wireless debugging. Return here when it is running."
            Setup.UNAUTHORIZED -> if (manualGrant)
                "In Shizuku, open Authorized applications and allow YinYang Phone EQ."
                else "Allow Phone EQ to process media and game audio while playback EQ is on."
            Setup.READY -> ""
        }
        installLinks.visible(next == Setup.MISSING)
        setupAction.visible(next != Setup.MISSING)
        setupAction.text = if (next == Setup.UNAUTHORIZED && !manualGrant) "Allow Shizuku" else "Open Shizuku"
    }

    private fun refresh() {
        val state = PlaybackService.status
        val next = shizukuSetup()
        if (setup != next) renderSetup(next)
        val ready = next == Setup.READY
        val reviewing = screen == Screen.REVIEW
        val browsing = screen == Screen.LIBRARY
        val preset = if (reviewing) incoming else selected
        home.visible(!browsing)
        libraryPanel.visible(browsing)
        homeButton.isSelected = screen == Screen.HOME
        libraryButton.isSelected = browsing
        companionButton.visible(companionReturn)
        setupPanel.visible(!ready && !reviewing && !browsing)
        presetPanel.visible(!browsing && (reviewing || ready || preset != null))
        openTxt.visible(!reviewing)
        if (backRegistered != (screen != Screen.HOME)) {
            backRegistered = screen != Screen.HOME
            if (backRegistered) onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, back)
            else onBackInvokedDispatcher.unregisterOnBackInvokedCallback(back)
        }
        if (playingPresetId != state.activePresetId) {
            playingPresetId = state.activePresetId
            rows.notifyDataSetChanged()
        }
        presetHeading.update(if (reviewing) "Review preset" else "Playback")
        presetName.visible(!reviewing)
        reviewForm.visible(reviewing)
        presetName.update(preset?.let { PresetInput.title(it.name) } ?: "No preset selected")
        presetHint.update(when {
            reviewing && skippedLines > 0 -> "${skippedLines} unsupported lines were skipped. Check the filters before saving."
            reviewing -> preset?.let { PresetKind.of(it).label }.orEmpty()
            preset == null -> "Choose a preset from Library or import a TXT file."
            else -> "${PresetKind.of(preset).label} • Selected preset"
        })
        val details = preset?.fit?.let {
            listOf("Headphone / IEM" to it.device, "Ear tips" to it.tips, "Tip size" to it.size,
                "Target" to it.target, "Fitter" to it.fitter)
                .filter { (_, value) -> value.isNotEmpty() }
                .joinToString("\n") { (label, value) -> "$label · $value" }
        }.orEmpty()
        fitDetails.update(details)
        fitDetails.visible(details.isNotEmpty())
        filters.visible(preset != null)
        playbackModes.visible(!reviewing && preset != null && PresetKind.of(preset) == PresetKind.PAIRED)
        modeButtons.forEach { (mode, control) -> control.isSelected = mode == pairedMode }
        exportActions.visible(!reviewing && preset != null)
        toggleFilters.visible(preset != null)
        toggleFilters.update(if (showFilters) "Hide filters" else "Show filters")
        filterDetails.visible(showFilters && preset != null)
        if (preset != null) {
            val counts = listOf(preset.commonBands.size, preset.leftBands.size, preset.rightBands.size)
            filterCounts.forEachIndexed { index, label -> label.update(counts[index].toString()) }
            if (showFilters && renderedDetails !== preset) {
                filterDetails.text = describeFilters(preset)
                renderedDetails = preset
            }
        }
        importNote.visible(importMessage.isNotEmpty())
        importNote.update(importMessage)
        refreshFooter(state, ready)
    }

    private fun refreshFooter(state: PlaybackService.Companion.Status, ready: Boolean) {
        val reviewing = screen == Screen.REVIEW
        val homeScreen = screen == Screen.HOME
        footer.visible(!keyboardVisible && (reviewing || state.busy || state.running || ready && homeScreen))
        reviewActions.visible(reviewing)
        playbackStatus.visible(ready && homeScreen || state.busy || state.running)
        playbackStatus.update(state.message)
        val mode = selected?.let { PresetPlaybackMode.forPreset(it, pairedMode) }
        val samePreset = state.activePresetId == selected?.id
        playback.visible(ready && !state.busy && homeScreen &&
            (!state.running || !samePreset || state.activeMode != mode))
        playback.isEnabled = selected != null && ready && !state.busy && !reviewing
        playback.update(when {
            !PlaybackService.notificationsEnabled(this) -> "Enable playback notifications"
            state.running && samePreset -> "Apply playback mode"
            state.running -> "Apply selected preset"
            else -> "Start phone EQ"
        })
        stop.visible(state.running || state.busy)
    }

    private fun describeFilters(preset: EqPreset): String = buildString {
        val sections = listOf(Triple("Main PEQ", preset.commonPreampDb, preset.commonBands),
            Triple("Left channel", preset.leftPreampDb, preset.leftBands),
            Triple("Right channel", preset.rightPreampDb, preset.rightBands))
        for ((name, preamp, bands) in sections) {
            if (bands.isEmpty() && preamp == 0.0) continue
            if (isNotEmpty()) append("\n\n")
            append(name)
            append(String.format(Locale.getDefault(), "\nPreamp %+.2f dB", preamp))
            for (band in bands) {
                append(String.format(Locale.getDefault(), "\n%s  %.1f Hz  %+.2f dB  Q %.2f",
                    band.type.apoCode, band.frequencyHz, band.gainDb, band.q))
                if (!band.enabled) append("  Off")
            }
        }
    }

    private fun View.visible(show: Boolean) { visibility = if (show) View.VISIBLE else View.GONE }
    private fun TextView.update(value: String) { if (text.toString() != value) text = value }

    private fun fromCompanion(intent: Intent): Boolean =
        intent.action == Intent.ACTION_SEND && (callingPackage in COMPANION_PACKAGES ||
            referrer?.let { it.scheme == "android-app" && it.host in COMPANION_PACKAGES } == true)

    private fun notifyUser(message: String) {
        importMessage = message
        if (screen == Screen.LIBRARY) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun receive(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        companionReturn = companionReturn || fromCompanion(intent)
        try {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT) ?: return
            if (text.length > PresetInput.LIMIT) {
                notifyUser("Choose a TXT file under 64 KB.")
                return
            }
            val title = intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: "Shared preset"
            stagePreset { PresetInput.parse(text.toString(), title) }
        } catch (_: RuntimeException) {
            notifyUser("Could not read the shared preset.")
        }
    }

    private fun stagePreset(read: () -> EqualizerApoParser.ParseResult) {
        val sequence = ++request
        reader.execute {
            val result = runCatching(read)
            handler.post {
                if (isDestroyed || sequence != request) return@post
                result.onSuccess {
                    if (screen != Screen.REVIEW) reviewParent = screen
                    incoming = it.preset
                    reviewName.setText(it.preset.name)
                    skippedLines = it.warnings.size
                    showFilters = false
                    importMessage = ""
                    navigate(Screen.REVIEW)
                    home.scrollTo(0, 0)
                }.onFailure {
                    notifyUser(if (it is IOException || it is IllegalArgumentException)
                        it.message ?: "Could not read that preset."
                    else "Could not open that preset.")
                }
                refresh()
            }
        }
    }

    private fun acceptPreset() {
        val preset = incoming?.copy(name = PresetInput.title(reviewName.text.toString())) ?: return
        library.saveAndSelect(preset)
        selected = preset
        pairedMode = PresetPlaybackMode.BOTH
        saved = library.load()
        updateLibrary()
        navigate(Screen.HOME)
        home.scrollTo(0, 0)
        Toast.makeText(this, "Saved to your preset library.", Toast.LENGTH_SHORT).show()
    }

    @Deprecated("Uses the Android system document picker.")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request == SAVE_LIBRARY || request == RESTORE_LIBRARY) {
            val uri = data?.data
            if (result != RESULT_OK || uri?.scheme != "content") return
            if (request == SAVE_LIBRARY) {
                writeDocument(uri, "Library backup saved.", "Could not save the library backup.") {
                    library.backup().encode()
                }
            } else {
                restoreLibrary(uri)
            }
            return
        }
        if (request == SAVE_TXT) {
            val text = pendingExport
            pendingExport = null
            val uri = data?.data
            if (result == RESULT_OK && uri?.scheme == "content" && text != null) {
                writeDocument(uri, "Preset TXT saved.", "Could not save the preset TXT. Choose another location.") {
                    text.toByteArray(Charsets.UTF_8)
                }
            }
            return
        }
        if (request != OPEN_TXT || result != RESULT_OK) return
        val uri = data?.data ?: return
        if (uri.scheme != "content") return
        stagePreset {
            val text = contentResolver.openInputStream(uri)?.use(PresetInput::read)
                ?: throw IOException("Could not open that file.")
            val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
            PresetInput.parse(text, name?.removeSuffix(".txt") ?: "Imported preset")
        }
    }

    @Suppress("DEPRECATION")
    private fun exportPreset() {
        val preset = selected ?: return
        pendingExport = PresetInput.export(preset)
        val picker = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/plain").putExtra(Intent.EXTRA_TITLE, PresetInput.exportFilename(preset.name))
        try {
            startActivityForResult(picker, SAVE_TXT)
        } catch (_: ActivityNotFoundException) {
            pendingExport = null
            Toast.makeText(this, "No app is available to save the preset TXT.", Toast.LENGTH_LONG).show()
        }
    }

    private fun offerLibraryRestore() {
        val preferences = getPreferences(MODE_PRIVATE)
        if (saved.isNotEmpty() || screen != Screen.HOME || intent.action == Intent.ACTION_SEND ||
            bugDialog?.isShowing == true || preferences.getBoolean(RESTORE_OFFER_SEEN, false)) return

        fun markSeen() = preferences.edit().putBoolean(RESTORE_OFFER_SEEN, true).apply()
        restoreOffer = AlertDialog.Builder(this)
            .setTitle("Import a previous library backup?")
            .setMessage("Choose a library backup made by Phone EQ. If this is your first time using the app, tap Skip.")
            .setPositiveButton("Import backup") { _, _ -> markSeen(); chooseLibraryBackup() }
            .setNegativeButton("Skip") { _, _ -> markSeen() }
            .setOnCancelListener { markSeen() }
            .show()
    }

    @Suppress("DEPRECATION")
    private fun chooseLibraryBackupDestination() {
        val picker = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json").putExtra(Intent.EXTRA_TITLE, "YinYang-Phone-EQ-Library.json")
        try {
            startActivityForResult(picker, SAVE_LIBRARY)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No file picker is available to save the backup.", Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun chooseLibraryBackup() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        try {
            startActivityForResult(picker, RESTORE_LIBRARY)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No file picker is available to open the backup.", Toast.LENGTH_LONG).show()
        }
    }

    private fun restoreLibrary(uri: Uri) {
        reader.execute {
            val restored = runCatching {
                val backup = contentResolver.openInputStream(uri)?.use(LibraryBackup::read)
                    ?: throw IOException("Could not open the library backup.")
                library.restore(backup)
            }
            handler.post {
                if (isDestroyed) return@post
                val message = restored.fold(
                    onSuccess = { count ->
                        reloadLibrary()
                        refresh()
                        when (count) {
                            0 -> "No new presets to restore."
                            1 -> "Restored 1 preset."
                            else -> "Restored $count presets."
                        }
                    },
                    onFailure = { error ->
                        if (error is IOException || error is IllegalArgumentException) error.message
                            ?: "Could not restore the library backup."
                        else "Choose a valid Phone EQ library backup file."
                    }
                )
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun writeDocument(uri: Uri, success: String, failure: String, contents: () -> ByteArray) {
        reader.execute {
            val result = runCatching {
                val bytes = contents()
                val stream = contentResolver.openOutputStream(uri, "wt")
                    ?: throw IOException("Could not open the export destination.")
                stream.use { it.write(bytes) }
            }
            handler.post {
                if (!isDestroyed) {
                    val error = result.exceptionOrNull()
                    val message = when (error) {
                        null -> success
                        is IllegalArgumentException -> error.message ?: failure
                        else -> failure
                    }
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startPlayback() {
        val preset = selected ?: return
        if (incoming != null || !PlaybackService.authorized()) return
        if (!PlaybackService.notificationsEnabled(this)) {
            val preferences = getPreferences(MODE_PRIVATE)
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                !preferences.getBoolean("askedForNotifications", false)) {
                preferences.edit().putBoolean("askedForNotifications", true).apply()
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
            } else {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }
            return
        }
        val mode = PresetPlaybackMode.forPreset(preset, pairedMode)
        val config = mode.config(preset)
        startForegroundService(Intent(this, PlaybackService::class.java).setAction(PlaybackService.START)
            .putExtra(PlaybackService.CONFIG, config.toJsonString()).putExtra(PlaybackService.NAME, preset.name)
            .putExtra(PlaybackService.PRESET_ID, preset.id).putExtra(PlaybackService.MODE, mode.name))
    }

    companion object {
        private const val OPEN_TXT = 1
        private const val SHIZUKU_REQUEST = 2
        private const val NOTIFICATION_REQUEST = 3
        private const val SAVE_TXT = 4
        private const val SAVE_LIBRARY = 5
        private const val RESTORE_LIBRARY = 6
        private const val RESTORE_OFFER_SEEN = "libraryRestoreOfferSeen"
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private val COMPANION_PACKAGES = setOf("com.yinyangeq.android.companion", "com.yinyangeq.companion.preview")
    }
}
