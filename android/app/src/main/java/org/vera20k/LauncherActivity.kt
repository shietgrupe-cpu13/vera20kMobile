package org.vera20k

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Intent
import android.content.ClipData
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.os.Environment
import android.util.Log
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.KeyEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Native Android command center. The Rust engine remains in [VeraActivity],
 * started only after the player presses Play. Keeping it in the separate
 * `:game` process means a launcher return never creates a second winit loop.
 */
class LauncherActivity : Activity() {
    private enum class Page(val title: String, val icon: Int) {
        HOME("Home", R.drawable.ic_ih_home),
        FILES("Game files", R.drawable.ic_ih_files),
        SETTINGS("Settings", R.drawable.ic_ih_settings),
        TOUCH("Touch", R.drawable.ic_ih_touch),
        MODS("Mods", R.drawable.ic_ih_mods),
        MAPS("Maps", R.drawable.ic_ih_maps),
        DIAGNOSTICS("Diagnostics", R.drawable.ic_ih_diagnostics),
    }

    private companion object {
        const val REQUEST_PICK_GAME = 0x564c
        const val REQUEST_PICK_MOD = 0x564d
        const val REQUEST_PICK_MAPS = 0x5650
        const val REQUEST_PICK_STORAGE = 0x5651
        const val REQUEST_PICK_ARES = 0x5652
        const val REQUEST_EXPORT_HUD = 0x564e
        const val REQUEST_IMPORT_HUD = 0x564f
        const val REQUIRED_FREE_BYTES = 1700L shl 20
        const val PROGRESS_STEP_BYTES = 16L shl 20
        const val ARES_RELEASE_URL = "https://raw.githubusercontent.com/Ares-Developers/Ares-release/4a4c4a247e61b5d5429113e7cb600423f4039abb/ares.mix"
        const val ARES_RELEASE_SHA256 = "1fdaa7ece6f079f92c4b4d7d7ce55fa7eeea6fa30c80119a31037315d18d6bb1"

        init {
            System.loadLibrary("vera20k_android")
        }
    }

    private var page = Page.HOME
    private var installation = LauncherInstallation(null, listOf("ra2.mix", "ra2md.mix"))
    private var modCatalog = LauncherModCatalog(emptyList(), null, LauncherIniProfile.YR, null)
    private var sharedModNames: List<String> = emptyList()
    private var sharedModScanGeneration = 0
    private var sharedModCatalogLoading = false
        private var aresSetting = "Auto"
    private var downloadingAres = false
    private var modSelectionNote: String? = null
    private var modImporting = false
    private var modImportNote: String? = null
    private var mapImporting = false
    private var mapCopiedCount = 0
    private var mapImportNote: String? = null
    private var modMapScanKey: String? = null
    private var modMapScanGeneration = 0
    private var modMapScanLoading = false
    private var modMapScanResult: List<LauncherModMapCatalog.MapFile> = emptyList()
    private var modMapStagingNote: String? = null
    private var validatingLaunch = false
    @Volatile private var launchCancellationRequested = false
    private var applyingModSelection = false
    private val installationRefreshGeneration = AtomicInteger()
    private val modSelectionGeneration = AtomicInteger()
    private val launcherIo = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ironharbor-launcher-io")
    }
    private val launchGate = GameLaunchGate()
    private var importing = false
    private var copiedBytes = 0L
    private var reportedBytes = 0L
    private var exportingDiagnostics = false
    private lateinit var pageBody: LinearLayout
    private lateinit var tabs: LinearLayout
    private lateinit var pageScroll: ScrollView
    private var lastRenderedPage: Page? = null
    private lateinit var playButton: TextView
    private lateinit var importButton: TextView
    private var importNote: String? = null
    private var storageNote: String? = null
    private var gameWasLaunched = false

    private external fun nativeConfigForImport(directory: String, filesDirectory: String): String
    private external fun nativeConfigForSelection(modName: String, profile: String, filesDirectory: String): String
    private external fun nativeConfigForMod(modName: String, filesDirectory: String): String
    private external fun nativeValidateCurrentSelection(filesDirectory: String): String
    private external fun nativeMapsDirectory(filesDirectory: String): String
    private external fun nativeGetAresSetting(filesDirectory: String): String
    private external fun nativeSetAresSetting(value: String, filesDirectory: String): String
    private external fun nativeGetRenderer(filesDirectory: String): String
    private external fun nativeSetRenderer(renderer: String, filesDirectory: String): String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = savedInstanceState?.getString("launcher_page")?.let { value ->
            Page.entries.firstOrNull { it.name == value }
        } ?: Page.HOME
        updateEngineDataRoot()
        buildShell()
        refreshInstallation()
        synchronizePersistedModMaps()
        showSupportPromptForBuild()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("launcher_page", page.name)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        installationRefreshGeneration.incrementAndGet()
        modSelectionGeneration.incrementAndGet()
        launcherIo.shutdownNow()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        launchGate.returnedToLauncher()
        updateEngineDataRoot()
        if (gameWasLaunched) {
            gameWasLaunched = false
            StartupDiagnostics.captureAfterGame(this)
        }
        if (::pageBody.isInitialized) {
            refreshInstallation()
            refreshSharedModCatalogAsync()
        }
    }

    @Deprecated("The SAF tree picker returns through this activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_STORAGE) {
            val tree = if (resultCode == RESULT_OK) data?.data else null
            if (tree == null) {
                storageNote = "Storage folder selection cancelled. Existing files were not changed."
            } else {
                val flags = (data?.flags ?: 0) and
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                try {
                    contentResolver.takePersistableUriPermission(tree, flags)
                    SharedDataStorage.setTree(this, tree)
                    SharedDataStorage(this).ensureLayout()
                    updateEngineDataRoot()
                    storageNote = "Documents/RA2Harbor is ready. Checking existing app data without replacing files..."
                    migrateExistingDataToDocuments()
                } catch (error: Exception) {
                    storageNote = "Cannot use that Documents folder: ${error.message ?: error.javaClass.simpleName}"
                }
            }
            renderPage()
            return
        }
        if (requestCode == REQUEST_PICK_MAPS) {
            val uris = if (resultCode == RESULT_OK && data != null) {
                buildList {
                    data.clipData?.let { clip ->
                        for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(::add)
                    }
                    if (isEmpty()) data.data?.let(::add)
                }.distinct()
            } else emptyList()
            if (uris.isEmpty()) {
                mapImportNote = "Map import cancelled. Your installed maps were not changed."
                page = Page.MAPS
                renderPage()
            } else importMaps(uris)
            return
        }
        if (requestCode == REQUEST_PICK_ARES) {
            val source = if (resultCode == RESULT_OK) data?.data else null
            if (source == null) {
                modImportNote = "Ares archive selection cancelled; existing files were left unchanged."
            } else {
                runCatching {
                    val flags = (data?.flags ?: 0) and Intent.FLAG_GRANT_READ_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(source, flags)
                    val name = SharedDataStorage(this).copyDocumentFile("Mods/AresSupport", source)
                    modImportNote = "Imported $name into the shared Ares support folder. It is used only with a selected mod."
                }.onFailure { error ->
                    modImportNote = "Ares archive was not installed: ${error.message ?: error.javaClass.simpleName}"
                }
            }
            page = Page.MODS
            renderPage()
            return
        }
        if (requestCode == REQUEST_PICK_MOD) {
            val tree = if (resultCode == RESULT_OK) data?.data else null
            if (tree == null) {
                modImportNote = "Mod import cancelled. Installed mods were not changed."
                renderPage()
                return
            }
            try {
                contentResolver.takePersistableUriPermission(
                    tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: SecurityException) {
                // The picker grant remains valid for this foreground import.
            }
            importMod(tree)
            return
        }
        if (requestCode == REQUEST_EXPORT_HUD || requestCode == REQUEST_IMPORT_HUD) {
            val uri = if (resultCode == RESULT_OK) data?.data else null
            if (uri == null) {
                Toast.makeText(this, "HUD layout transfer cancelled.", Toast.LENGTH_SHORT).show()
                return
            }
            if (requestCode == REQUEST_EXPORT_HUD) exportHudLayout(uri) else importHudLayout(uri)
            return
        }
        if (requestCode != REQUEST_PICK_GAME) return
        val tree = if (resultCode == RESULT_OK) data?.data else null
        if (tree == null) {
            importNote = "Import cancelled. Your current game folder was not changed."
            renderPage()
            return
        }
        try {
            contentResolver.takePersistableUriPermission(
                tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            importNote = "Android did not grant persistent access. Select a local shared-storage folder again."
            renderPage()
            return
        }
        importGame(tree)
    }

    private fun buildShell() {
        val baseLeft = dp(20)
        val baseTop = dp(12)
        val baseRight = dp(20)
        val baseBottom = dp(8)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.launcher_background))
            setPadding(baseLeft, baseTop, baseRight, baseBottom)
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val safeInsets = android.graphics.Rect()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                )
                safeInsets.set(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                val cutout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) insets.displayCutout else null
                safeInsets.set(
                    maxOf(insets.systemWindowInsetLeft, cutout?.safeInsetLeft ?: 0),
                    maxOf(insets.systemWindowInsetTop, cutout?.safeInsetTop ?: 0),
                    maxOf(insets.systemWindowInsetRight, cutout?.safeInsetRight ?: 0),
                    maxOf(insets.systemWindowInsetBottom, cutout?.safeInsetBottom ?: 0),
                )
            }
            view.setPadding(
                baseLeft + safeInsets.left,
                baseTop + safeInsets.top,
                baseRight + safeInsets.right,
                baseBottom + safeInsets.bottom,
            )
            insets
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(8))
        }
        val logo = ImageView(this).apply {
            setImageResource(R.mipmap.iron_harbor_beacon_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Iron Harbor Mobile app icon"
        }
        header.addView(logo, LinearLayout.LayoutParams(dp(52), dp(52)))
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        brand.addView(text("IRON HARBOR", 18, R.color.launcher_text, bold = true, tracking = 0.08f))
        brand.addView(text("MOBILE COMMAND   /   ${appVersion()}", 10, R.color.launcher_muted, tracking = 0.06f))
        header.addView(brand, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(text("GPLv3", 11, R.color.launcher_accent, bold = true, tracking = 0.08f))
        root.addView(header)

        val compactNavigation = resources.configuration.screenWidthDp < 600
        val navScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            isFillViewport = compactNavigation
        }
        tabs = LinearLayout(this).apply {
            orientation = if (compactNavigation) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        }
        navScroll.addView(
            tabs,
            ViewGroup.LayoutParams(if (compactNavigation) -1 else -2, -2),
        )
        root.addView(navScroll, LinearLayout.LayoutParams(-1, dp(if (compactNavigation) 100 else 48)))

        pageScroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(0, dp(8), 0, dp(12))
        }
        val centered = FrameLayout(this)
        pageBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(20))
        }
        val maxWidth = minOf(dp(960), resources.displayMetrics.widthPixels - dp(44))
        centered.addView(pageBody, FrameLayout.LayoutParams(maxWidth, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        pageScroll.addView(centered)
        root.addView(pageScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        // Accessing Window.insetsController before setContentView can dereference
        // a missing DecorView on Android 16. The launcher must first install its
        // root so the window controller exists before we request visible bars.
        showLauncherSystemBars()
        root.requestApplyInsets()
        renderPage()
    }

    private fun showLauncherSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Target SDK 36 is edge-to-edge on Android 15+, so the root view
            // applies insets while both system bars remain visible.
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.show(WindowInsets.Type.systemBars())
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    private fun renderPage() {
        if (!::pageBody.isInitialized) return
        val pageChanged = lastRenderedPage != page
        renderTabs()
        pageBody.animate().cancel()
        pageBody.alpha = 0.72f
        pageBody.removeAllViews()
        when (page) {
            Page.HOME -> renderHome()
            Page.FILES -> renderFiles()
            Page.SETTINGS -> renderSettings()
            Page.TOUCH -> renderTouch()
            Page.MODS -> renderMods()
            Page.MAPS -> renderMaps()
            Page.DIAGNOSTICS -> renderDiagnostics()
        }
        lastRenderedPage = page
        if (pageChanged) pageScroll.post { pageScroll.scrollTo(0, 0) }
        pageBody.animate().alpha(1f).setDuration(140).start()
    }

    private fun renderTabs() {
        tabs.removeAllViews()
        val compactNavigation = resources.configuration.screenWidthDp < 600
        tabs.orientation = if (compactNavigation) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        fun createTab(item: Page): TextView {
            val selected = item == page
            return TextView(this).apply {
                text = item.title
                textSize = if (compactNavigation) 9f else 11f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = if (compactNavigation) 0f else 0.015f
                gravity = Gravity.CENTER
                setTextColor(color(if (selected) R.color.launcher_accent else R.color.launcher_muted))
                setPadding(dp(if (compactNavigation) 3 else 13), dp(2), dp(if (compactNavigation) 3 else 13), dp(2))
                compoundDrawablePadding = dp(if (compactNavigation) 1 else 7)
                val glyph = getDrawable(item.icon)?.mutate()?.apply {
                    setTint(color(if (selected) R.color.launcher_accent else R.color.launcher_muted))
                }
                if (compactNavigation) setCompoundDrawablesWithIntrinsicBounds(null, glyph, null, null)
                else setCompoundDrawablesRelativeWithIntrinsicBounds(glyph, null, null, null)
                background = rounded(
                    if (selected) color(R.color.launcher_surface_alt) else color(R.color.launcher_background),
                    if (selected) color(R.color.launcher_accent_dark) else color(R.color.launcher_background),
                    10,
                )
                isFocusable = true
                isClickable = true
                isSelected = selected
                contentDescription = item.title
                setOnClickListener { page = item; renderPage() }
            }
        }
        if (compactNavigation) {
            Page.entries.chunked(4).forEach { pageRow ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                pageRow.forEach { item ->
                    row.addView(createTab(item), LinearLayout.LayoutParams(0, dp(48), 1f))
                }
                repeat(4 - pageRow.size) {
                    row.addView(View(this), LinearLayout.LayoutParams(0, dp(48), 1f))
                }
                tabs.addView(row, LinearLayout.LayoutParams(-1, dp(50)))
            }
        } else {
            Page.entries.forEach { item ->
                val params = LinearLayout.LayoutParams(-2, dp(42))
                params.setMargins(0, 0, dp(6), 0)
                tabs.addView(createTab(item), params)
            }
        }
    }

    private fun renderHome() {
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(20), dp(14), dp(20))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(color(R.color.launcher_surface_alt), color(R.color.launcher_surface), color(R.color.launcher_background)),
            ).apply {
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), color(R.color.launcher_border))
            }
        }
        val heroCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heroCopy.addView(kicker("INDEPENDENT MOBILE CLIENT  /  ${appVersion()}"))
        heroCopy.addView(text("IRON HARBOR\nFIELD COMMAND", 27, R.color.launcher_text, bold = true, tracking = 0.025f))
        heroCopy.addView(text("Based on YuriPlanet VERA20K", 13, R.color.launcher_muted), margin(top = 6))
        hero.addView(heroCopy, LinearLayout.LayoutParams(0, -2, 1f))
        hero.addView(ImageView(this).apply {
            setImageResource(R.mipmap.iron_harbor_beacon_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Iron Harbor beacon and harbor gate emblem"
        }, LinearLayout.LayoutParams(dp(92), dp(92)))
        pageBody.addView(hero, margin(bottom = 14))
        pageBody.addView(text(
            "Your legally obtained game files stay on this device and are never bundled with the app.",
            14,
            R.color.launcher_muted,
        ), margin(bottom = 14))

        val stateCard = card()
        val ready = installation.ready
        stateCard.addView(kicker(if (ready) "INSTALLATION READY" else "SETUP REQUIRED", if (ready) R.color.launcher_accent else R.color.launcher_warning))
        stateCard.addView(text(
            if (ready && installation.iniProfile == "ra2") "Red Alert 2 files detected"
            else if (ready) "Yuri's Revenge files detected" else "Add your own game files",
            19,
            R.color.launcher_text,
            bold = true,
        ))
        stateCard.addView(text(
            if (ready) installation.configuredDirectory?.absolutePath.orEmpty()
            else if (installation.iniProfile == "ra2") "The launcher checks the configured folder for ra2.mix."
            else "The launcher checks the engine's configured folder for ra2.mix and ra2md.mix.",
            12,
            R.color.launcher_muted,
        ), margin(top = 5))
        stateCard.addView(text(
            "Play will use: ${modCatalog.selectedMod ?: "Vanilla Yuri's Revenge"}  /  ${modCatalog.profile.title} profile",
            13,
            if (modCatalog.containsSelectedMod()) R.color.launcher_accent else R.color.launcher_warning,
            bold = true,
        ), margin(top = 9))
        if (!modCatalog.containsSelectedMod()) {
            stateCard.addView(text(
                "The selected mod folder is missing. Choose another mod before starting.",
                12,
                R.color.launcher_warning,
            ), margin(top = 4))
        }
        pageBody.addView(stateCard, margin(bottom = 12))

        playButton = actionButton(if (validatingLaunch) "CHECKING GAME FILES..." else "START GAME", primary = true) {
            if (validatingLaunch) return@actionButton
            if (installation.ready) launchGame() else {
                importNote = "Locate your game folder before launching."
                page = Page.FILES
                renderPage()
            }
        }
        playButton.isEnabled = installation.ready && !importing && !validatingLaunch && !launchGate.isPending
        pageBody.addView(playButton, margin(bottom = 10))
        if (validatingLaunch) {
            pageBody.addView(ProgressBar(this).apply { isIndeterminate = true }, margin(bottom = 6))
            pageBody.addView(text("Checking paths and launcher configuration; archive indexing is performed once by the game engine after launch.", 12, R.color.launcher_muted), margin(bottom = 6))
            pageBody.addView(actionButton("CANCEL STARTUP CHECK", primary = false) {
                launchCancellationRequested = true
                renderPage()
            }, margin(bottom = 10))
        }
        pageBody.addView(actionButton("GAME FILES   /   IMPORT OR CHANGE", primary = false) {
            page = Page.FILES
            renderPage()
        }, margin(bottom = 17))

        val engine = card()
        engine.addView(kicker("ENGINE STATUS"))
        infoRow(engine, "Engine", "VERA20K ${appVersion()}")
        infoRow(engine, "Renderer", "Engine-selected Android backend")
        infoRow(engine, "Attribution", "YuriPlanet / VERA20K  /  GPLv3")
        pageBody.addView(engine)
        pageBody.addView(text(
            "Unofficial community fork. Not affiliated with or endorsed by Electronic Arts.",
            10,
            R.color.launcher_muted,
        ), margin(top = 14))
    }

    private fun renderFiles() {
        pageBody.addView(kicker("GAME LIBRARY"))
        pageBody.addView(text("Game files", 30, R.color.launcher_text, bold = true))
        pageBody.addView(text(
            "Select your game folder in place. Save, map, mod, log and config data stays in Documents/RA2Harbor.",
            14,
            R.color.launcher_muted,
        ), margin(bottom = 15))

        val storageCard = card()
        storageCard.addView(kicker("USER-MANAGED STORAGE"))
        val sharedStorage = SharedDataStorage(this)
        val storageLocation = sharedStorage.displayLocation()
        storageCard.addView(text(storageLocation, 16, R.color.launcher_text, bold = true))
        storageCard.addView(text(
            if (sharedStorage.treeUri() == null) "Game files stay in their selected source folder. Saves, maps, mods, logs and configuration use $storageLocation automatically."
            else "Game files stay in their selected source folder. Saves, maps, mods, logs and configuration use $storageLocation.",
            13,
            R.color.launcher_muted,
        ), margin(top = 6))
        storageCard.addView(actionButton("CHOOSE DOCUMENTS FOLDER", primary = false) { chooseStorageLocation() }, margin(top = 8))
        val allFilesAccess = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
        storageCard.addView(text(
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) "In-place engine access requires Android 11 or newer in this build. SAF-only providers cannot be passed to the Rust filesystem."
            else if (allFilesAccess) "All files access: granted. Android still protects other apps' private folders."
            else "All files access: not granted. Grant it so the engine can read game/mod folders directly and keep saves, maps, logs and configuration in Documents/RA2Harbor without private copies.",
            12,
            if (allFilesAccess) R.color.launcher_accent else R.color.launcher_muted,
        ), margin(top = 9))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !allFilesAccess) {
            storageCard.addView(actionButton("GRANT ALL FILES ACCESS", primary = false) {
                AlertDialog.Builder(this)
                    .setTitle("Allow access to shared storage?")
                    .setMessage("Android will open its special app-access screen. This lets VERA20K read your selected game/mod folders directly and write user data under Documents/RA2Harbor. It does not grant access to other apps? private folders. You can revoke access in Settings.")
                    .setNegativeButton("NOT NOW", null)
                    .setPositiveButton("OPEN SETTINGS") { _, _ -> openAllFilesAccessSettings() }
                    .show()
            }, margin(top = 7))
        }
        storageNote?.let { storageCard.addView(text(it, 12, R.color.launcher_muted), margin(top = 8)) }
        pageBody.addView(storageCard, margin(bottom = 12))

        val status = card()
        val ready = installation.ready
        status.addView(kicker(if (ready) "VALIDATION PASSED" else "VALIDATION NEEDED", if (ready) R.color.launcher_accent else R.color.launcher_warning))
        status.addView(text(
            if (ready) "Required engine archives are present" else "Required files not confirmed",
            18,
            R.color.launcher_text,
            bold = true,
        ))
        val shared = SharedDataStorage(this)
        val gameSource = shared.gameSourceName()
        val sharedGame = shared.activeGameFolder()
        val location = gameSource?.let { "Selected source: $it (read in place)" }
            ?: sharedGame?.let { "Documents/RA2Harbor (legacy shared data)" }
            ?: installation.configuredDirectory?.absolutePath
            ?: "No configured game folder"
        status.addView(text(location, 12, R.color.launcher_muted), margin(top = 6))
        if (!ready) {
            status.addView(text(
                if (installation.configuredDirectory == null) "Missing configuration. Import an installation to set it up."
                else "Missing: ${installation.missingArchives.joinToString()}",
                12,
                R.color.launcher_warning,
            ), margin(top = 7))
        }
        pageBody.addView(status, margin(bottom = 12))

        if (importing) {
            val progressCard = card()
            progressCard.addView(kicker("PREPARING GAME FILES"))
            progressCard.addView(text(importNote ?: "Copying game files...", 15, R.color.launcher_text))
            progressCard.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = true
            }, margin(top = 10, bottom = 8))
            progressCard.addView(actionButton("CANCEL PREPARATION", primary = false) { AssetImportJob.cancelActive() })
            pageBody.addView(progressCard, margin(bottom = 12))
        } else {
            importButton = actionButton(if (ready) "LOCATE / CHANGE GAME FOLDER" else "LOCATE GAME FILES", primary = true) {
                chooseGameFolder()
            }
            pageBody.addView(importButton, margin(bottom = 12))
            if (importNote != null) {
                pageBody.addView(text(importNote!!, 13, R.color.launcher_warning), margin(bottom = 10))
            }
        }

        val guide = card()
        guide.addView(kicker("WHAT THE CHECKER USES"))
        infoRow(guide, "Required", if (modCatalog.profile == LauncherIniProfile.RA2) "ra2.mix" else "ra2.mix and ra2md.mix")
        infoRow(guide, "Storage", "Original game folder is read in place; no game copy is made")
        infoRow(guide, "Game data", "Not bundled with this app")
        pageBody.addView(guide)
    }

    private fun renderSettings() {
        pageBody.addView(kicker("ENGINE-BACKED OPTIONS"))
        pageBody.addView(text("Game settings", 30, R.color.launcher_text, bold = true))
        pageBody.addView(text(
            "Only controls implemented by the current VERA20K engine are shown here. These Android display options do not alter simulation state.",
            14,
            R.color.launcher_muted,
        ), margin(bottom = 14))

        val display = card()
        display.addView(kicker("DISPLAY & RENDERER"))
        val rendererValues = listOf("auto", "vulkan", "opengl")
        val rendererLabels = listOf("Automatic", "Vulkan", "OpenGL ES")
        val dataDirectory = engineDataDirectory()
        val requestedRenderer = runCatching {
            nativeGetRenderer(dataDirectory.absolutePath)
        }.getOrDefault("auto")
        val visibleRenderer = requestedRenderer.takeIf { it in rendererValues } ?: "auto"
        display.addView(text("Game renderer (next launch)", 16, R.color.launcher_text, bold = true), margin(top = 7))
        val renderer = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@LauncherActivity,
                android.R.layout.simple_spinner_dropdown_item,
                rendererLabels,
            )
            setSelection(rendererValues.indexOf(visibleRenderer).coerceAtLeast(0))
        }
        renderer.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = rendererValues[position.coerceIn(rendererValues.indices)]
                if (selected == visibleRenderer) return
                try {
                    nativeSetRenderer(selected, dataDirectory.absolutePath)
                    Toast.makeText(
                        this@LauncherActivity,
                        "Renderer saved. It applies on the next game launch; startup diagnostics report any fallback.",
                        Toast.LENGTH_LONG,
                    ).show()
                } catch (error: Exception) {
                    Toast.makeText(
                        this@LauncherActivity,
                        "Renderer was not changed: ${error.message ?: error.javaClass.simpleName}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        display.addView(renderer, margin(top = 3))
        display.addView(text(
            "Automatic uses the engine's Android default. Vulkan and OpenGL ES are explicit wgpu backends; the selected value is persisted in the engine-owned VERA.INI and applies after restarting gameplay.",
            12,
            R.color.launcher_muted,
        ), margin(top = 4, bottom = 8))
        val displayPreferences = getSharedPreferences(
            ImmersiveFullscreenSettings.PREFERENCES,
            MODE_PRIVATE,
        )
        val immersiveFullscreen = CheckBox(this).apply {
            text = "Immersive fullscreen gameplay"
            setTextColor(color(R.color.launcher_text))
            contentDescription = "Hide Android status and navigation bars during gameplay"
            isChecked = displayPreferences.getBoolean(
                ImmersiveFullscreenSettings.ENABLED,
                ImmersiveFullscreenSettings.DEFAULT_ENABLED,
            )
        }
        display.addView(immersiveFullscreen, margin(top = 7))
        display.addView(text(
            "When enabled, gameplay hides the system bars. Swipe from an edge to reveal them temporarily. The launcher and Android file picker keep their normal system bars.",
            12,
            R.color.launcher_muted,
        ), margin(top = 2, bottom = 7))
        immersiveFullscreen.setOnCheckedChangeListener { _, checked ->
            displayPreferences.edit()
                .putBoolean(ImmersiveFullscreenSettings.ENABLED, checked)
                .apply()
            Toast.makeText(
                this,
                if (checked) "Immersive fullscreen applies the next time gameplay starts"
                else "System bars will remain visible during gameplay after the next launch",
                Toast.LENGTH_SHORT,
            ).show()
        }
        infoRow(display, "Orientation", "Landscape-first Android window")
        infoRow(display, "Backend status", "The actual adapter and fallback are recorded by engine startup diagnostics")
        infoRow(display, "Physical resolution", "Unchanged")
        display.addView(text(
            "Internal render scale, FPS limit, adaptive resolution, and camera zoom limits are not exposed because this engine revision does not read those settings. No performance gain is claimed.",
            12,
            R.color.launcher_muted,
        ), margin(top = 8))
        pageBody.addView(display, margin(bottom = 12))

        val audio = card()
        audio.addView(kicker("AUDIO"))
        infoRow(audio, "Output", "Engine default; device volume controls")
        infoRow(audio, "Mixer settings", "No launcher control in this build")
        pageBody.addView(audio)
    }
    private fun renderTouch() {
        val controlPrefs = getSharedPreferences(TouchHudSettings.PREFS, MODE_PRIVATE)
        val hudCard = card()
        hudCard.addView(kicker("FLOATING COMMAND HUD"))
        hudCard.addView(text("RA2YRPORT-inspired floating controls", 19, R.color.launcher_text, bold = true))
        hudCard.addView(text("Customize from the in-game EDIT button or arrange controls in this preview. EDIT and HIDE/SHOW can also be moved. Tap an empty C1-C10 to assign the current selection, tap a filled group to select it, and hold to add selected units or clear the group when nothing is selected.", 14, R.color.launcher_muted), margin(top = 6, bottom = 8))
        val enabled = CheckBox(this).apply {
            text = "Show floating HUD in touch and mixed modes"
            setTextColor(color(R.color.launcher_text))
            isChecked = controlPrefs.getBoolean(TouchHudSettings.HUD_ENABLED, true)
        }
        hudCard.addView(enabled)
        hudCard.addView(actionButton("CUSTOMIZE AND ARRANGE BUTTONS", primary = true) {
            showTouchHudEditor()
        }, margin(top = 8))
        hudCard.addView(actionButton("REMAP ACTION KEYS", primary = false) {
            chooseTouchHudKey()
        }, margin(top = 7))
        enabled.setOnCheckedChangeListener { _, checked ->
            controlPrefs.edit().putBoolean(TouchHudSettings.HUD_ENABLED, checked).apply()
        }
        pageBody.addView(hudCard, margin(bottom = 12))

        pageBody.addView(kicker("INPUT PROFILE"))
        pageBody.addView(text("Touch controls", 30, R.color.launcher_text, bold = true))
        pageBody.addView(text("VERA20K keeps its native touch-to-input mapper. These gestures feed the existing mouse and camera controls without changing simulation rules.", 14, R.color.launcher_muted), margin(bottom = 14))
        val gestures = card()
        gestures.addView(kicker("SUPPORTED GESTURES"))
        infoRow(gestures, "Tap", "Select or activate")
        infoRow(gestures, "Quick drag", "Camera pan during a match")
        infoRow(gestures, "Hold, then drag", "Box selection")
        infoRow(gestures, "Two-finger drag", "Camera pan")
        infoRow(gestures, "Pinch", "Presentation zoom")
        infoRow(gestures, "Two-finger tap", "Context / cancel action")
        pageBody.addView(gestures, margin(bottom = 12))

        val inputModeCard = card()
        inputModeCard.addView(kicker("INPUT DEVICE MODE"))
        inputModeCard.addView(text("Choose how game input is accepted", 19, R.color.launcher_text, bold = true))
        inputModeCard.addView(text("Touch-only keeps screen gestures and the floating HUD. Mixed accepts both touch and external devices. External-only disables touch gestures and the HUD. This takes effect when the game is next launched.", 13, R.color.launcher_muted), margin(top = 6, bottom = 7))
        val modes = listOf("Touch only", "Touch + mouse / keyboard", "Mouse + keyboard only")
        val modeValues = listOf(TouchHudSettings.MODE_TOUCH, TouchHudSettings.MODE_MIXED, TouchHudSettings.MODE_EXTERNAL)
        val mode = Spinner(this).apply {
            adapter = ArrayAdapter(this@LauncherActivity, android.R.layout.simple_spinner_dropdown_item, modes)
            setSelection(modeValues.indexOf(TouchHudSettings.parseControlMode(
                controlPrefs.getString(TouchHudSettings.CONTROL_MODE, null),
            )).coerceAtLeast(0))
        }
        mode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                controlPrefs.edit().putString(TouchHudSettings.CONTROL_MODE, modeValues[position]).apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        inputModeCard.addView(mode)
        pageBody.addView(inputModeCard, margin(bottom = 12))

        val layout = card()
        layout.addView(kicker("MOVE AVAILABLE GAME UI"))
        layout.addView(text("Edit Layout", 19, R.color.launcher_text, bold = true))
        layout.addView(text(
            "In a match, open Options > Interface > Edit Layout. Drag supported panels and layout-added buttons, then choose Save. Reset restores the current game's default layout.",
            14,
            R.color.launcher_muted,
        ), margin(top = 7))
        layout.addView(text("The floating command HUD has its own in-game EDIT button; drag every HUD control, including EDIT and HIDE/SHOW, there or use the preview above.", 13, R.color.launcher_muted), margin(top = 9))
        pageBody.addView(layout)
    }

    private fun showTouchHudEditor() {
        val prefs = getSharedPreferences(TouchHudSettings.PREFS, MODE_PRIVATE)
        val visible = TouchHudSettings.parseVisible(
            prefs.getString(TouchHudSettings.HUD_VISIBLE, null),
            prefs.contains(TouchHudSettings.HUD_VISIBLE),
        ).toMutableSet()
        val choices = TouchHudSettings.buttons
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), dp(4))
        }
        root.addView(text("Choose visible controls, assign them to Always / Set 1 / Set 2, then switch sets from the in-game HUD. Drag, resize, and transfer your setup when needed.", 13, R.color.launcher_muted), margin(bottom = 8))
        val checkboxes = mutableListOf<Pair<String, CheckBox>>()
        val controlSets = TouchHudSettings.parseControlSets(prefs.getString(TouchHudSettings.HUD_CONTROL_SETS, null)).toMutableMap()
        val sizes = TouchHudSettings.parseSizes(prefs.getString(TouchHudSettings.HUD_SIZES, null)).toMutableMap()
        val buttonOpacities = TouchHudSettings.parseButtonOpacities(
            prefs.getString(TouchHudSettings.HUD_BUTTON_OPACITIES, null),
        ).toMutableMap()
        val buttonLabels = TouchHudSettings.parseButtonLabels(
            prefs.getString(TouchHudSettings.HUD_BUTTON_LABELS, null),
        ).toMutableMap()
        val positions = TouchHudSettings.parsePositions(prefs.getString(TouchHudSettings.HUD_POSITIONS, null)).toMutableMap()
        var positionedLayout = prefs.getInt(TouchHudSettings.HUD_LAYOUT, TouchHudSettings.LAYOUT_FREE)
        var positionedDock = prefs.getInt(TouchHudSettings.HUD_DOCK, 0)
        val sizeOptions = listOf(70, 85, 100, 115, 130, 150)
        choices.forEach { action ->
            val check = CheckBox(this).apply {
                val bound = prefs.contains("key_${action.id}") || action.defaultKeyCode != KeyEvent.KEYCODE_UNKNOWN
                text = if (bound) action.label else "${action.label}   /   assign an existing game key first"
                setTextColor(color(R.color.launcher_text))
                isChecked = action.id in visible
            }
            check.setOnCheckedChangeListener { _, checked ->
                if (checked) visible.add(action.id) else visible.remove(action.id)
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(check, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (action.groupNumber == null && action.id !in TouchHudSettings.setSelectorButtons.map { it.id }) {
                val setSpinner = Spinner(this).apply {
                    adapter = ArrayAdapter(this@LauncherActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Always", "Set 1", "Set 2"))
                    setSelection((controlSets[action.id] ?: 0).coerceIn(0, 2))
                }
                setSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                        controlSets[action.id] = position
                    }
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                }
                row.addView(setSpinner, LinearLayout.LayoutParams(dp(112), ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            val sizeSpinner = Spinner(this).apply {
                adapter = ArrayAdapter(this@LauncherActivity, android.R.layout.simple_spinner_dropdown_item, sizeOptions.map { "$it%" })
                val currentSize = sizes[action.id] ?: 100
                setSelection(sizeOptions.indexOf(currentSize).takeIf { it >= 0 } ?: 2)
            }
            sizeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    sizes[action.id] = sizeOptions[position]
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
            row.addView(sizeSpinner, LinearLayout.LayoutParams(dp(78), ViewGroup.LayoutParams.WRAP_CONTENT))
            root.addView(row)
            checkboxes += action.id to check
        }
        fun seekRow(title: String, initial: Int, min: Int, max: Int, onLabel: (Int) -> String): Pair<SeekBar, TextView> {
            val label = text(onLabel(initial), 13, R.color.launcher_text)
            root.addView(label, margin(top = 7))
            val seek = SeekBar(this).apply {
                this.max = max - min
                progress = initial.coerceIn(min, max) - min
                contentDescription = title
            }
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    label.text = onLabel(min + progress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
            root.addView(seek)
            return seek to label
        }
        val scale = seekRow("Global HUD size", TouchHudSettings.clampScale(prefs.getInt(TouchHudSettings.HUD_SCALE, 100)), 70, 150) { "Global button size  /  $it%" }.first
        val opacity = seekRow("HUD opacity", TouchHudSettings.clampOpacity(prefs.getInt(TouchHudSettings.HUD_OPACITY, 88)), 30, 100) { "Button opacity  /  $it%" }.first
        root.addView(text("Layout preset", 13, R.color.launcher_text), margin(top = 8))
        val layoutNames = listOf("Free / saved layout", "Two-thumb layout", "Yuri controls layout")
        val layout = Spinner(this).apply {
            adapter = ArrayAdapter(this@LauncherActivity, android.R.layout.simple_spinner_dropdown_item, layoutNames)
            setSelection(prefs.getInt(TouchHudSettings.HUD_LAYOUT, TouchHudSettings.LAYOUT_FREE).coerceIn(0, layoutNames.lastIndex))
        }
        root.addView(layout)
        root.addView(text("Button dock", 13, R.color.launcher_text), margin(top = 5))
        val dock = Spinner(this).apply {
            adapter = ArrayAdapter(this@LauncherActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Bottom left", "Left edge", "Bottom right"))
            setSelection(prefs.getInt(TouchHudSettings.HUD_DOCK, 0).coerceIn(0, 2))
        }
        root.addView(dock)
        root.addView(text("A preset resets button positions to its starting arrangement. Place them on the launcher preview before saving.", 12, R.color.launcher_muted), margin(top = 3, bottom = 5))
        root.addView(actionButton("DRAG AND SIZE BUTTONS IN PREVIEW", primary = true) {
            val previewVisible = visible.toMutableSet()
            val previewPositions = positions.toMutableMap()
            val previewSizes = sizes.toMutableMap()
            val previewOpacities = buttonOpacities.toMutableMap()
            val previewLabels = buttonLabels.toMutableMap()
            val layoutChanged = positionedLayout != layout.selectedItemPosition || positionedDock != dock.selectedItemPosition
            if (layoutChanged) previewPositions.clear()
            TouchHudLayoutEditor(this).show(
                previewVisible,
                previewPositions,
                previewSizes,
                controlSets.toMap(),
                layout.selectedItemPosition,
                dock.selectedItemPosition,
                scale.progress + 70,
                opacity.progress + 30,
                previewOpacities,
                previewLabels,
            ) {
                visible.clear()
                visible.addAll(previewVisible)
                positions.clear()
                positions.putAll(previewPositions)
                sizes.clear()
                sizes.putAll(previewSizes)
                buttonOpacities.clear()
                buttonOpacities.putAll(previewOpacities)
                buttonLabels.clear()
                buttonLabels.putAll(previewLabels)
                positionedLayout = layout.selectedItemPosition
                positionedDock = dock.selectedItemPosition
                checkboxes.forEach { (id, check) -> check.isChecked = id in visible }
            }
        }, margin(top = 7, bottom = 6))
        val backupButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val export = Button(this).apply {
            text = "Export HUD"
            isAllCaps = false
            minHeight = dp(48)
            compoundDrawablePadding = dp(8)
            setCompoundDrawablesRelativeWithIntrinsicBounds(
                getDrawable(R.drawable.ic_ih_download)?.mutate()?.apply { setTint(color(R.color.launcher_accent)) },
                null, null, null,
            )
        }
        val import = Button(this).apply {
            text = "Import HUD"
            isAllCaps = false
            minHeight = dp(48)
            compoundDrawablePadding = dp(8)
            setCompoundDrawablesRelativeWithIntrinsicBounds(
                getDrawable(R.drawable.ic_ih_files)?.mutate()?.apply { setTint(color(R.color.launcher_accent)) },
                null, null, null,
            )
        }
        backupButtons.addView(export, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        backupButtons.addView(import, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(backupButtons)
        export.setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, "IronHarbor-HUD-Layout.ihhud")
            }, REQUEST_EXPORT_HUD)
        }
        import.setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, REQUEST_IMPORT_HUD)
        }
        val scroll = ScrollView(this).apply { addView(root) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Customize floating HUD")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save HUD", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selected = checkboxes.filter { it.second.isChecked }.mapTo(linkedSetOf()) { it.first }
                val previousLayout = prefs.getInt(TouchHudSettings.HUD_LAYOUT, TouchHudSettings.LAYOUT_FREE)
                val previousDock = prefs.getInt(TouchHudSettings.HUD_DOCK, 0)
                if ((previousLayout != layout.selectedItemPosition || previousDock != dock.selectedItemPosition) &&
                    (positionedLayout != layout.selectedItemPosition || positionedDock != dock.selectedItemPosition)
                ) {
                    positions.clear()
                }
                val edit = prefs.edit()
                    .putString(TouchHudSettings.HUD_VISIBLE, TouchHudSettings.encodeVisible(selected))
                    .putString(TouchHudSettings.HUD_CONTROL_SETS, TouchHudSettings.encodeControlSets(controlSets))
                    .putString(TouchHudSettings.HUD_SIZES, TouchHudSettings.encodeSizes(sizes))
                    .putString(TouchHudSettings.HUD_BUTTON_OPACITIES, TouchHudSettings.encodeButtonOpacities(buttonOpacities))
                    .putString(TouchHudSettings.HUD_BUTTON_LABELS, TouchHudSettings.encodeButtonLabels(buttonLabels))
                    .putString(TouchHudSettings.HUD_POSITIONS, TouchHudSettings.encodePositions(positions))
                    .putInt(TouchHudSettings.HUD_SCALE, TouchHudSettings.clampScale(scale.progress + 70))
                    .putInt(TouchHudSettings.HUD_OPACITY, TouchHudSettings.clampOpacity(opacity.progress + 30))
                    .putInt(TouchHudSettings.HUD_LAYOUT, layout.selectedItemPosition)
                    .putInt(TouchHudSettings.HUD_DOCK, dock.selectedItemPosition)
                    .putBoolean(TouchHudSettings.HUD_ENABLED, true)
                edit.apply()
                dialog.dismiss()
                Toast.makeText(this, "HUD settings saved. They apply the next time you start a match.", Toast.LENGTH_LONG).show()
            }
        }
        dialog.show()
    }

    private fun chooseTouchHudKey() {
        val actions = TouchHudSettings.remappableActions
        AlertDialog.Builder(this)
            .setTitle("Choose command to remap")
            .setItems(actions.map { it.label }.toTypedArray()) { _, which -> captureTouchHudKey(actions[which].id) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportHudLayout(uri: Uri) {
        val prefs = getSharedPreferences(TouchHudSettings.PREFS, MODE_PRIVATE)
        val payload = TouchHudSettings.encodeLayoutBackup(
            TouchHudSettings.parseVisible(prefs.getString(TouchHudSettings.HUD_VISIBLE, null), prefs.contains(TouchHudSettings.HUD_VISIBLE)),
            TouchHudSettings.parsePositions(prefs.getString(TouchHudSettings.HUD_POSITIONS, null)),
            TouchHudSettings.parseSizes(prefs.getString(TouchHudSettings.HUD_SIZES, null)),
            prefs.getInt(TouchHudSettings.HUD_SCALE, 100),
            prefs.getInt(TouchHudSettings.HUD_OPACITY, 88),
            prefs.getInt(TouchHudSettings.HUD_LAYOUT, TouchHudSettings.LAYOUT_FREE),
            TouchHudSettings.remappableActions.mapNotNull { action ->
                if (prefs.contains("key_${action.id}")) action.id to prefs.getInt("key_${action.id}", 0) else null
            }.toMap(),
            TouchHudSettings.parseControlSets(prefs.getString(TouchHudSettings.HUD_CONTROL_SETS, null)),
            prefs.getInt(TouchHudSettings.HUD_DOCK, 0),
            prefs.getInt(TouchHudSettings.HUD_ACTIVE_SET, 1),
            prefs.getBoolean(TouchHudSettings.HUD_HIDDEN, false),
            TouchHudSettings.parseButtonOpacities(prefs.getString(TouchHudSettings.HUD_BUTTON_OPACITIES, null)),
            TouchHudSettings.parseButtonLabels(prefs.getString(TouchHudSettings.HUD_BUTTON_LABELS, null)),
        )
        runCatching { (contentResolver.openOutputStream(uri, "wt") ?: error("Selected document could not be opened")).bufferedWriter().use { it.write(payload) } }
            .onSuccess { Toast.makeText(this, "HUD layout exported.", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(this, "Could not export HUD layout: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    private fun importHudLayout(uri: Uri) {
        val payload = runCatching {
            val input = contentResolver.openInputStream(uri) ?: error("Selected document could not be opened")
            input.bufferedReader().use { reader ->
                val content = StringBuilder()
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (content.length + count > 65536) error("HUD layout file is larger than 64 KB")
                    content.append(buffer, 0, count)
                }
                content.toString()
            }
        }.getOrElse {
            Toast.makeText(this, "Could not read HUD layout: ${it.message}", Toast.LENGTH_LONG).show()
            return
        }
        val imported = TouchHudSettings.decodeLayoutBackup(payload)
        if (imported == null) {
            Toast.makeText(this, "That file is not a supported Iron Harbor HUD layout.", Toast.LENGTH_LONG).show()
            return
        }
        val edit = getSharedPreferences(TouchHudSettings.PREFS, MODE_PRIVATE).edit()
            .putString(TouchHudSettings.HUD_VISIBLE, TouchHudSettings.encodeVisible(imported.visible))
            .putString(TouchHudSettings.HUD_CONTROL_SETS, TouchHudSettings.encodeControlSets(imported.controlSets))
            .putString(TouchHudSettings.HUD_POSITIONS, TouchHudSettings.encodePositions(imported.positions))
            .putString(TouchHudSettings.HUD_SIZES, TouchHudSettings.encodeSizes(imported.sizes))
            .putString(TouchHudSettings.HUD_BUTTON_OPACITIES, TouchHudSettings.encodeButtonOpacities(imported.buttonOpacities))
            .putString(TouchHudSettings.HUD_BUTTON_LABELS, TouchHudSettings.encodeButtonLabels(imported.buttonLabels))
            .putInt(TouchHudSettings.HUD_SCALE, imported.scale)
            .putInt(TouchHudSettings.HUD_OPACITY, imported.opacity)
            .putInt(TouchHudSettings.HUD_LAYOUT, imported.layout)
            .putInt(TouchHudSettings.HUD_DOCK, imported.dock)
            .putInt(TouchHudSettings.HUD_ACTIVE_SET, imported.activeSet)
            .putBoolean(TouchHudSettings.HUD_HIDDEN, imported.hidden)
            .putBoolean(TouchHudSettings.HUD_ENABLED, true)
        TouchHudSettings.remappableActions.forEach { edit.remove("key_${it.id}") }
        imported.keyBindings.forEach { (id, keyCode) -> edit.putInt("key_$id", keyCode) }
        edit.apply()
        Toast.makeText(this, "HUD layout imported. It applies when the next match starts.", Toast.LENGTH_LONG).show()
    }

    private fun captureTouchHudKey(id: String) {
        val prefs = getSharedPreferences(TouchHudSettings.PREFS, MODE_PRIVATE)
        val action = TouchHudSettings.buttons.first { it.id == id }
        val code = prefs.getInt("key_$id", action.defaultKeyCode)
        val prompt = TextView(this).apply {
            val currentKey = if (code == KeyEvent.KEYCODE_UNKNOWN) "Not bound" else KeyEvent.keyCodeToString(code)
            text = "Current key: $currentKey\n\nTap this panel, then press the physical keyboard key already assigned to ${action.label} in the game. The HUD sends that key through VERA20K's existing engine input path."
            textSize = 16f
            setTextColor(color(R.color.launcher_text))
            setPadding(dp(24), dp(24), dp(24), dp(24))
            isFocusable = true
            isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN || keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_UNKNOWN) return@setOnKeyListener false
                if ((event.source and android.view.InputDevice.SOURCE_KEYBOARD) != android.view.InputDevice.SOURCE_KEYBOARD) return@setOnKeyListener true
                prefs.edit().putInt("key_$id", keyCode).apply()
                Toast.makeText(this@LauncherActivity, "${action.label} bound to ${KeyEvent.keyCodeToString(keyCode)}", Toast.LENGTH_SHORT).show()
                true
            }
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Bind ${action.label}")
            .setView(prompt)
            .setPositiveButton("Done", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener { prompt.requestFocus() }
        dialog.show()
    }

    private fun renderMods() {
        pageBody.addView(kicker("CONTENT PROFILES"))
        pageBody.addView(text("Mods", 30, R.color.launcher_text, bold = true))
        val current = card()
        current.addView(kicker("ENGINE CONFIGURATION"))
        if (applyingModSelection) {
            current.addView(ProgressBar(this).apply { isIndeterminate = true }, margin(top = 6))
            current.addView(text("Applying the latest selection in the background…", 13, R.color.launcher_muted), margin(top = 4))
        }
        current.addView(text(
            "Next launch: ${modCatalog.selectedMod ?: "Vanilla Yuri's Revenge"}",
            16,
            R.color.launcher_text,
            bold = true,
        ))
        current.addView(text(
            "INI profile: ${modCatalog.profile.title}",
            14,
            R.color.launcher_muted,
        ), margin(top = 5))
        current.addView(text(
            "Selections are saved through the engine config writer and read when the game process starts. They do not replace resources in an active match.",
            13,
            R.color.launcher_muted,
        ), margin(top = 8))
        if (!installation.ready) {
            current.addView(text(
                "Locate and validate a game installation before changing the active mod or INI profile.",
                13,
                R.color.launcher_warning,
            ), margin(top = 8))
        }
        if (modCatalog.profile == LauncherIniProfile.AUTO) {
            current.addView(text(
                "Auto uses the mod.ini resource declarations when present, then the engine's automatic INI-root rules.",
                13,
                R.color.launcher_muted,
            ), margin(top = 8))
        }
        if (modCatalog.profile == LauncherIniProfile.MO) {
            current.addView(text(
                "Mental Omega selects the MO roots and archive family from its mod identity. Explicit [Files] and [Archives] entries in mod.ini override corresponding defaults. Engine archive indexing happens once during game startup.",
                13,
                R.color.launcher_muted,
            ), margin(top = 8))
        }
        if (modCatalog.configError != null) {
            current.addView(text(
                "Cannot read config.toml: ${modCatalog.configError}",
                13,
                R.color.launcher_warning,
            ), margin(top = 8))
        }
        if (!modCatalog.containsSelectedMod()) {
            current.addView(text(
                "The configured mod folder is missing. Select Vanilla or an installed folder to repair the choice.",
                13,
                R.color.launcher_warning,
            ), margin(top = 8))
        }
        pageBody.addView(current, margin(bottom = 12))

        val profiles = card()
        profiles.addView(kicker("SUPPORTED INI ROOT PROFILES"))
        val availableProfiles = buildList {
            add(LauncherIniProfile.RA2)
            add(LauncherIniProfile.YR)
            if (modCatalog.selectedMod != null) add(LauncherIniProfile.MO)
            add(LauncherIniProfile.AUTO)
        }
        availableProfiles.forEach { profile ->
            profiles.addView(actionButton(
                if (modCatalog.profile == profile) "  ${profile.title.uppercase()}   /   SELECTED" else profile.title.uppercase(),
                primary = modCatalog.profile == profile,
            ) { applyModSelection(modCatalog.selectedMod, profile) }, margin(top = 7))
        }
        if (modCatalog.profile == LauncherIniProfile.CUSTOM) {
            profiles.addView(text(
                "Custom is already selected in engine config. Editing its INI roots in the launcher is not supported yet.",
                13,
                R.color.launcher_warning,
            ), margin(top = 8))
        }
        pageBody.addView(profiles, margin(bottom = 12))

        val installed = card()
        installed.addView(kicker("INSTALLED DATA MODS"))
        installed.addView(actionButton(
            if (modImporting) "IMPORTING MOD..." else "IMPORT MOD FOLDER",
            primary = false,
        ) { if (!modImporting) chooseModFolder() }, margin(top = 7))
        if (modImporting) installed.addView(text(
            "Validating the selected folder in place. The source files are not copied or moved.",
            13, R.color.launcher_muted,
        ), margin(top = 7))
        installed.addView(text(
            "Select a mod folder from local shared storage. Mods under Documents/RA2Harbor/Mods are discovered automatically; other selected folders can be used in place. The launcher does not duplicate mod files. DLL or executable patches are not run by VERA20K.",
            13,
            R.color.launcher_muted,
        ), margin(top = 8))
        installed.addView(actionButton(
            if (modCatalog.selectedMod == null) "  VANILLA YURI'S REVENGE   /   SELECTED" else "USE VANILLA YURI'S REVENGE",
            primary = modCatalog.selectedMod == null,
        ) { applyModSelection(null, null) }, margin(top = 10))
        if (sharedModCatalogLoading) installed.addView(text("Checking Documents/RA2Harbor/Mods for new or nested mod folders...", 13, R.color.launcher_muted), margin(top = 8))
        if (modCatalog.installedMods.isEmpty() && !sharedModCatalogLoading) {
            installed.addView(text("No mod folders found under Documents/RA2Harbor/Mods.", 13, R.color.launcher_muted), margin(top = 8))
        }
        modCatalog.installedMods.forEach { folder ->
            val folderLabel = File(folder).name.takeIf { it.isNotBlank() } ?: folder
            installed.addView(text(folderLabel, 15, R.color.launcher_text, bold = true), margin(top = 13))
            installed.addView(actionButton(
                if (folder.equals(modCatalog.selectedMod, ignoreCase = true)) "  SELECTED FOR NEXT LAUNCH" else "USE THIS MOD",
                primary = folder.equals(modCatalog.selectedMod, ignoreCase = true),
            ) { applyModSelection(folder, null) }, margin(top = 5))
        }
        pageBody.addView(installed)

        val ares = card()
        ares.addView(kicker("SHARED ARES SUPPORT"))
        ares.addView(text("One archive folder for selected mods", 18, R.color.launcher_text, bold = true))
        val archive = File(engineDataDirectory(), "Mods/AresSupport/ares.mix")
        ares.addView(text(
            if (archive.isFile) "ares.mix is ready  /  ${archive.length() / 1024} KB" else "No ares.mix installed",
            14,
            if (archive.isFile) R.color.launcher_accent else R.color.launcher_muted,
        ), margin(top = 5))
        ares.addView(text(
            "Add an optional shared Ares archive under Documents/RA2Harbor/Mods/AresSupport. It is used only for a selected mod; that mod's own ares.mix takes priority. Vanilla Yuri's Revenge ignores it.",
            13,
            R.color.launcher_muted,
        ), margin(top = 8))
        ares.addView(actionButton("IMPORT ARES.MIX", primary = false) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }, REQUEST_PICK_ARES)
        }, margin(top = 8))
        ares.addView(actionButton(
            if (downloadingAres) "DOWNLOADING OFFICIAL ARES..." else "DOWNLOAD / UPDATE OFFICIAL ARES ARCHIVE",
            primary = false,
        ) { if (!downloadingAres) downloadOfficialAresArchive() }, margin(top = 6))
        if (modCatalog.selectedMod == null) {
            ares.addView(text("Select a mod to enable Ares controls. Vanilla stays isolated.", 13, R.color.launcher_muted), margin(top = 8))
        } else {
            ares.addView(text("Ares mode: $aresSetting  /  saved for the next game launch", 14, R.color.launcher_text, bold = true), margin(top = 9))
            listOf("Auto", "On", "Off").forEach { setting ->
                ares.addView(actionButton(
                    if (aresSetting.equals(setting, ignoreCase = true)) "  ARES $setting   /   SELECTED" else "ARES $setting",
                    primary = aresSetting.equals(setting, ignoreCase = true),
                ) { applyAresSetting(setting) }, margin(top = 6))
            }
            ares.addView(text("Auto loads the shared archive when it exists. Off disables Ares for this mod. Changes apply on the next launch, before resources and INIs load.", 12, R.color.launcher_muted), margin(top = 7))
        }
        pageBody.addView(ares, margin(top = 12))
        modSelectionNote?.let { note ->
            pageBody.addView(text(note, 13, R.color.launcher_muted), margin(top = 10))
        }
        modImportNote?.let { note ->
            pageBody.addView(text(note, 13, R.color.launcher_muted), margin(top = 8))
        }
    }

    private fun renderMaps() {
        pageBody.addView(kicker("CUSTOM SCENARIOS"))
        pageBody.addView(text("Maps", 30, R.color.launcher_text, bold = true))
        pageBody.addView(text(
            "Add your own map files. The engine scans this folder in Choose Map after the game and active mod folders.",
            14,
            R.color.launcher_muted,
        ), margin(bottom = 14))

        val location = runCatching { File(nativeMapsDirectory(engineDataDirectory().absolutePath)).canonicalFile }
        val mapDirectory = location.getOrNull()
        val card = card()
        card.addView(kicker("ENGINE MAP LIBRARY"))
        card.addView(text(mapDirectory?.absolutePath ?: "Engine map folder unavailable", 12, R.color.launcher_muted))
        card.addView(text("Editable folder: Documents/RA2Harbor/Maps", 12, R.color.launcher_accent), margin(top = 6))
        modMapStagingNote?.let { note ->
            card.addView(text(note, 13, R.color.launcher_muted), margin(top = 6))
        }
        if (mapDirectory == null) {
            card.addView(text(
                "The current config.toml could not be resolved. Fix the engine configuration before importing maps.",
                13,
                R.color.launcher_warning,
            ), margin(top = 8))
        } else {
            val maps = mapDirectory.listFiles()?.filter { file ->
                file.isFile && file.extension.lowercase(Locale.ROOT) in setOf("mmx", "yro", "map", "mpr", "yrm")
            }?.sortedBy { it.name.lowercase(Locale.ROOT) }.orEmpty()
            card.addView(text("${maps.size} map file${if (maps.size == 1) "" else "s"} available", 15, R.color.launcher_text, bold = true), margin(top = 8))
            if (maps.isEmpty()) {
                card.addView(text("No custom maps imported yet.", 13, R.color.launcher_muted), margin(top = 5))
            } else {
                maps.forEach { file ->
                    card.addView(text(file.name, 14, R.color.launcher_text), margin(top = 7))
                }
            }
        }
        pageBody.addView(card, margin(bottom = 12))

        renderActiveModMaps()

        if (mapImporting) {
            val progress = card()
            progress.addView(kicker("IMPORT IN PROGRESS"))
            progress.addView(text("Imported $mapCopiedCount map file${if (mapCopiedCount == 1) "" else "s"}...", 14, R.color.launcher_text))
            progress.addView(ProgressBar(this).apply { isIndeterminate = true }, margin(top = 9, bottom = 8))
            progress.addView(actionButton("CANCEL MAP IMPORT", primary = false) {
                MapImportJob.cancelActive()
                mapImportNote = "Cancelling after the current file; completed imports will remain available."
                renderPage()
            })
            pageBody.addView(progress, margin(bottom = 12))
        } else if (mapDirectory != null) {
            pageBody.addView(actionButton("ADD CUSTOM MAPS", primary = true) {
                chooseMaps()
            }, margin(bottom = 10))
        }
        pageBody.addView(text(
            "Supported engine formats: .mmx, .yro, .map, .mpr, and .yrm. Existing files are never replaced; name conflicts receive a numbered filename. Game map assets are not included.",
            12,
            R.color.launcher_muted,
        ))
        mapImportNote?.let { note ->
            pageBody.addView(text(note, 13, R.color.launcher_muted), margin(top = 10))
        }
    }

    /** Show the selected mod's source map tree and explain its temporary engine copies. */
    private fun renderActiveModMaps() {
        val selected = modCatalog.selectedMod
        val card = card()
        card.addView(kicker("ACTIVE MOD MAPS"))
        if (selected == null) {
            card.addView(text("Vanilla Yuri's Revenge is selected. Select a mod to view its map files.", 13, R.color.launcher_muted))
            pageBody.addView(card, margin(bottom = 12))
            return
        }

        card.addView(text(selected, 14, R.color.launcher_accent, bold = true))
        val root = selectedModDirectory(selected)
        if (root == null) {
            card.addView(text("The selected mod folder is not currently accessible. Its files were not copied.", 13, R.color.launcher_warning), margin(top = 6))
            pageBody.addView(card, margin(bottom = 12))
            return
        }
        val key = runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
        if (modMapScanKey != key || modMapScanLoading) {
            card.addView(text(if (modMapScanLoading) "Scanning the mod folder in place..." else "Map list is being refreshed...", 13, R.color.launcher_muted), margin(top = 6))
            scheduleModMapScan(root, key)
        } else if (modMapScanResult.isEmpty()) {
            card.addView(text("No supported loose map files were found in this mod folder.", 13, R.color.launcher_muted), margin(top = 6))
        } else {
            val groups = modMapScanResult.groupingBy { it.group }.eachCount().toSortedMap(String.CASE_INSENSITIVE_ORDER)
            val engineMaps = modMapScanResult.count { File(it.relativePath).extension.lowercase(Locale.ROOT) in setOf("map", "yrm", "mpr") }
            card.addView(text(
                "$engineMaps engine map${if (engineMaps == 1) "" else "s"} are mirrored temporarily into Documents/RA2Harbor/Maps. The original mod files stay in place; unselecting the mod removes only unchanged Iron Harbor copies.",
                13,
                R.color.launcher_muted,
            ), margin(top = 6))
            groups.forEach { (group, count) ->
                card.addView(text("$group  ·  $count", 12, R.color.launcher_text), margin(top = 5))
            }
            card.addView(actionButton("VIEW MOD MAPS", primary = false) {
                showModMapList(selected, modMapScanResult)
            }, margin(top = 10))
        }
        card.addView(actionButton("REFRESH MOD MAP LIST", primary = false) {
            modMapScanKey = null
            modMapScanResult = emptyList()
            renderPage()
        }, margin(top = 8))
        pageBody.addView(card, margin(bottom = 12))
    }

    private fun selectedModDirectory(selected: String): File? {
        val data = engineDataDirectory()
        val candidates = buildList {
            add(File(selected))
            add(File(data, selected))
            add(File(File(data, "Mods"), selected))
            SharedDataStorage(this@LauncherActivity).externalModPath()?.let { add(File(it)) }
        }
        return candidates.firstNotNullOfOrNull { candidate ->
            runCatching { candidate.canonicalFile.takeIf { it.isDirectory } }.getOrNull()
        }
    }

    private fun scheduleModMapScan(root: File, key: String) {
        if (modMapScanLoading && modMapScanKey == key) return
        val generation = ++modMapScanGeneration
        modMapScanKey = key
        modMapScanLoading = true
        Thread({
            val maps = LauncherModMapCatalog.scan(root)
            runOnUiThread {
                if (generation != modMapScanGeneration || isFinishing || isDestroyed) return@runOnUiThread
                modMapScanLoading = false
                modMapScanResult = maps
                if (page == Page.MAPS) renderPage()
            }
        }, "ironharbor-mod-map-scan").start()
    }

    private fun showModMapList(modName: String, maps: List<LauncherModMapCatalog.MapFile>) {
        val list = ListView(this).apply {
            adapter = ArrayAdapter(
                this@LauncherActivity,
                android.R.layout.simple_list_item_1,
                maps.map { "${it.group}  /  ${it.title}\n${it.relativePath}" },
            )
            divider = null
            setOnItemClickListener { _, _, position, _ ->
                Toast.makeText(this@LauncherActivity, "Map stays at ${maps[position].relativePath} in $modName.", Toast.LENGTH_LONG).show()
            }
        }
        AlertDialog.Builder(this)
            .setTitle("$modName maps (${maps.size})")
            .setView(list)
            .setPositiveButton("CLOSE", null)
            .show()
    }

    private fun chooseMaps() {
        if (!ensureSharedStorage()) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(intent, REQUEST_PICK_MAPS)
    }

    private fun importMaps(uris: List<Uri>) {
        val job = MapImportJob.begin()
        if (job == null) {
            mapImportNote = "Another custom map import is already running."
            renderPage()
            return
        }
        page = Page.MAPS
        mapImporting = true
        mapCopiedCount = 0
        mapImportNote = "Preparing selected map files..."
        renderPage()
        Thread({
            var result: String
            var completedCount = 0
            try {
                val sources = uris.map { uri ->
                    val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }?.takeIf { it.isNotBlank() } ?: throw IOException("The selected provider did not supply a filename")
                    MapImportJob.Source(name) { destination ->
                        job.checkCancelled()
                        contentResolver.openInputStream(uri)?.use { input ->
                            destination.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    job.checkCancelled()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                }
                            }
                        } ?: throw IOException("Cannot read $name")
                    }
                }
                val dataDirectory = requireSharedDataDirectory()
                val mapsDirectory = File(nativeMapsDirectory(dataDirectory.absolutePath))
                val imported = job.run(mapsDirectory, sources) { count ->
                    completedCount = count
                    runOnUiThread {
                        mapCopiedCount = count
                        if (page == Page.MAPS) renderPage()
                    }
                }
                result = "Imported ${imported.size} map file${if (imported.size == 1) "" else "s"} into Documents/RA2Harbor/Maps. The engine reads maps from this folder directly."
            } catch (_: MapImportJob.Cancelled) {
                result = "Map import cancelled. $completedCount completed file${if (completedCount == 1) " remains" else "s remain"}; existing files were not replaced."
            } catch (error: Throwable) {
                Log.e("IronHarborImport", "Custom map import failed", error)
                result = "Map import stopped: ${error.message ?: error.javaClass.simpleName}. $completedCount completed file${if (completedCount == 1) " remains" else "s remain"}."
            } finally {
                job.close()
            }
            runOnUiThread {
                mapImporting = false
                mapImportNote = result
                renderPage()
            }
        }, "vera20k-map-import").start()
    }

    private fun renderDiagnostics() {
        pageBody.addView(kicker("DEVICE & ENGINE"))
        pageBody.addView(text("Diagnostics", 30, R.color.launcher_text, bold = true))
        val activityManager = getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memory)
        val device = card()
        device.addView(kicker("COMPATIBILITY SNAPSHOT"))
        infoRow(device, "Device", "${Build.MANUFACTURER} ${Build.MODEL}")
        infoRow(device, "Android", "${Build.VERSION.RELEASE}  /  API ${Build.VERSION.SDK_INT}")
        infoRow(device, "ABI", Build.SUPPORTED_ABIS.joinToString())
        infoRow(device, "Requested renderer", rendererTitle(runCatching {
            nativeGetRenderer(engineDataDirectory().absolutePath)
        }.getOrDefault("auto")))
        infoRow(device, "Actual adapter", "See the engine startup log for adapter and fallback")
        infoRow(device, "Memory", "${memory.availMem / (1024 * 1024)} MB available  /  native heap ${Debug.getNativeHeapAllocatedSize() / (1024 * 1024)} MB")
        infoRow(device, "Performance profile", "In-game Options > Performance Stats shows frame/tick rate, CPU phases, core load and GPU timing")
        val preflight = File(engineDataDirectory(), "Logs/launcher-preflight-latest.txt")
        infoRow(device, "File/launch timing", if (preflight.isFile) preflight.readLines().take(5).joinToString("  /  ") else "Recorded after the next Play preflight")
        infoRow(device, "FPS comparison", "Use the in-game CPU/GPU overlay and matching workloads; the diagnostics ZIP does not yet record live frame samples")
        infoRow(device, "Log export", "Full retained engine logs and crash report in a ZIP")
        val automaticDiagnostics = CheckBox(this).apply {
            text = "Automatic startup diagnostics"
            setTextColor(color(R.color.launcher_text))
            contentDescription = "Capture and overwrite the startup diagnostic report when the launcher opens or returns from gameplay"
            isChecked = StartupDiagnostics.autoCaptureEnabled(this@LauncherActivity)
            setOnCheckedChangeListener { _, enabled ->
                StartupDiagnostics.setAutoCaptureEnabled(this@LauncherActivity, enabled)
                Toast.makeText(
                    this@LauncherActivity,
                    if (enabled) "Automatic startup diagnostics enabled" else "Automatic startup diagnostics disabled",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        device.addView(automaticDiagnostics, margin(top = 8, bottom = 2))
        device.addView(text(
            "${StartupDiagnostics.locationDescription(this)}. When enabled, the same file is overwritten on launcher start and after gameplay. Disabling this stops those automatic snapshots; engine logs, manual ZIP export, and crash reports remain available.",
            12,
            R.color.launcher_muted,
        ), margin(bottom = 8))
        pageBody.addView(device, margin(bottom = 12))
        pageBody.addView(actionButton("SHARE AUTOMATIC STARTUP LOG", primary = false) {
            StartupDiagnostics.shareLatest(this)
        }.apply { isEnabled = Build.VERSION.SDK_INT >= 29 })
        pageBody.addView(actionButton(
            if (exportingDiagnostics) "PREPARING DIAGNOSTICS ZIP..." else "EXPORT FULL DIAGNOSTICS ZIP",
            primary = false,
        ) {
            shareDiagnostics(memory)
        }.apply { isEnabled = !exportingDiagnostics })
    }

    private fun shareDiagnostics(memory: ActivityManager.MemoryInfo) {
        if (exportingDiagnostics) return
        val report = buildString {
            appendLine("Iron Harbor Mobile diagnostics (based on YuriPlanet VERA20K)")
            appendLine("Version: ${appVersion()}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Requested renderer: ${rendererTitle(runCatching { nativeGetRenderer(engineDataDirectory().absolutePath) }.getOrDefault("auto"))}")
            appendLine("Actual renderer: inspect the engine startup log for adapter and fallback")
            appendLine("Available memory: ${memory.availMem / (1024 * 1024)} MB")
            appendLine("Native heap: ${Debug.getNativeHeapAllocatedSize() / (1024 * 1024)} MB")
            appendLine("Installation configured: ${installation.configuredDirectory?.absolutePath ?: "no"}")
            appendLine("Engine logs: ${File(engineDataDirectory(), "Logs").absolutePath}")
            val preflight = File(engineDataDirectory(), "Logs/launcher-preflight-latest.txt")
            if (preflight.isFile) {
                appendLine("Last game-file/mod preflight timings:")
                appendLine(preflight.readText().take(16_384))
            } else {
                appendLine("No launcher preflight timing has been recorded yet.")
            }
            appendLine("The attached ZIP includes recognized engine logs, retained crash/ANR details when available, a bounded startup diagnostic report, and recent app-UID logcat.")
            appendLine("The logcat capture cannot include system-wide logs or guarantee that entries remain available.")
            appendLine("No game assets are included in this report.")
        }
        exportingDiagnostics = true
        renderPage()
        Toast.makeText(this, "Preparing the full diagnostics ZIP...", Toast.LENGTH_SHORT).show()
        Thread({
            try {
                val logcat = AndroidLogcatCapture.capture(android.os.Process.myUid(), cacheDir)
                val startupDiagnostics = StartupDiagnostics.readLatest(this)
                val bundle = DiagnosticLogArchive.create(
                    engineDataDirectory(), cacheDir, report, logcat, startupDiagnostics,
                )
                val uri = Uri.Builder()
                    .scheme("content")
                    .authority("$packageName.diagnostics")
                    .appendPath("diagnostics")
                    .appendPath(bundle.file.name)
                    .build()
                runOnUiThread {
                    exportingDiagnostics = false
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (page == Page.DIAGNOSTICS) renderPage()
                    if (bundle.logFileCount == 0 && !bundle.crashReportIncluded) {
                        Toast.makeText(this, "No saved engine logs yet; the ZIP contains the device summary.", Toast.LENGTH_LONG).show()
                    }
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_SUBJECT, "Iron Harbor Mobile full diagnostics")
                        putExtra(Intent.EXTRA_TEXT, report)
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newUri(contentResolver, bundle.file.name, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    val chooser = Intent.createChooser(send, "Share full diagnostics")
                    chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(chooser)
                }
            } catch (error: Exception) {
                runOnUiThread {
                    exportingDiagnostics = false
                    if (!isFinishing && !isDestroyed) {
                        if (page == Page.DIAGNOSTICS) renderPage()
                        Toast.makeText(this, "Could not export diagnostics: ${error.message ?: error.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }, "diagnostic-log-export").start()
    }

    private fun refreshInstallation() {
        val data = engineDataDirectory()
        val generation = installationRefreshGeneration.incrementAndGet()
        val extraMods = sharedModNames.toList()
        launcherIo.execute {
            if (generation != installationRefreshGeneration.get()) return@execute
            val result = runCatching {
                Triple(
                    LauncherInstallation.read(data),
                    readModCatalog(data, extraMods),
                    runCatching { nativeGetAresSetting(data.absolutePath) }.getOrDefault("Auto"),
                )
            }
            runOnUiThread {
                if (generation != installationRefreshGeneration.get() || isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { (nextInstallation, nextMods, nextAresSetting) ->
                    installation = nextInstallation
                    modCatalog = nextMods
                    aresSetting = nextAresSetting
                }.onFailure { error ->
                    Log.e("IronHarborLauncher", "Could not refresh installation and mod selection", error)
                    modCatalog = modCatalog.copy(configError = error.message ?: "Could not read launcher settings")
                }
                renderPage()
            }
        }
    }

    private fun engineDataDirectory(): File = SharedDataStorage(this).directRootPath()
        ?: rememberedEngineDataRoot()
        ?: filesDir

    private fun rememberedEngineDataRoot(): File? = runCatching {
        val path = File(filesDir, "launcher-data-root.txt").readText().trim()
        File(path).takeIf { it.isAbsolute && path.isNotEmpty() }
    }.getOrNull()

    private fun readModCatalog(directory: File, additionalMods: List<String> = sharedModNames): LauncherModCatalog {
        val externalMod = SharedDataStorage(this).externalModPath()
        return LauncherModCatalog.read(directory)
            .withAdditionalMods(additionalMods + listOfNotNull(externalMod))
    }

    private fun requireSharedDataDirectory(): File = SharedDataStorage(this).directRootPath()
        ?: throw IOException(
            rememberedEngineDataRoot()?.let { "Access to the selected shared data folder was revoked: ${it.absolutePath}. Restore access or choose that folder again; existing data was not moved." }
                ?: "Grant Android All files access to use Documents/RA2Harbor."
        )

    /** Point NativeActivity at the same public data directory the launcher edits. */
    private fun updateEngineDataRoot() {
        val pointer = File(filesDir, "launcher-data-root.txt")
        val target = SharedDataStorage(this).directRootPath()
        if (target == null) {
            if (pointer.isFile) {
                val selected = rememberedEngineDataRoot()?.absolutePath ?: "the selected shared folder"
                storageNote = "Android access to $selected is unavailable. Existing files and the saved folder selection were preserved; restore All files access or choose the folder again."
            }
            return
        }
        runCatching {
            migratePrivateUserData(target)
            val value = target.canonicalPath
            if (!pointer.isFile || pointer.readText() != value) {
                val temporary = File(filesDir, ".launcher-data-root.tmp")
                FileOutputStream(temporary).use { output ->
                    output.write(value.toByteArray(Charsets.UTF_8))
                    output.fd.sync()
                }
                java.nio.file.Files.move(
                    temporary.toPath(), pointer.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }
        }.onFailure { error ->
            storageNote = "Shared data folder is not ready: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    /** Merge old app-owned user data once by copying only missing names; retain every source. */
    private fun migratePrivateUserData(target: File) {
        val roots = listOf("Mods", "Maps", "Saves", "saves", "Logs", "logs", "AresSupport")
        for (name in roots) {
            val destination = when (name) {
                "saves" -> "Saves"
                "logs" -> "Logs"
                "Maps" -> "Maps"
                "AresSupport" -> "Mods/AresSupport"
                else -> name
            }
            copyMissingTree(File(filesDir, name), File(target, destination))
        }
        for (name in listOf("config.toml", "VERA.INI")) {
            val source = File(filesDir, name)
            val destinationName = if (name == "config.toml") "config.toml" else "VERA.INI"
            val destination = File(target, "Config/$destinationName")
            val legacyDestination = File(target, destinationName)
            // A root-level config is a supported read-only migration source.
            // Never copy private settings into Config/ and thereby shadow it.
            if (source.isFile && !destination.exists() && !legacyDestination.exists()) {
                destination.parentFile?.mkdirs()
                runCatching { source.copyTo(destination, overwrite = false) }
            }
        }
    }

    private fun copyMissingTree(source: File, destination: File) {
        if (!source.isDirectory || java.nio.file.Files.isSymbolicLink(source.toPath()) || source.canonicalFile == destination.canonicalFile) return
        if (!destination.exists() && !destination.mkdirs()) return
        source.listFiles()?.forEach { entry ->
            if (java.nio.file.Files.isSymbolicLink(entry.toPath())) return@forEach
            val target = File(destination, entry.name)
            if (target.exists() && entry.isDirectory && target.isDirectory) copyMissingTree(entry, target)
            else if (target.exists()) return@forEach
            else if (entry.isDirectory) copyMissingTree(entry, target)
            else if (entry.isFile) runCatching { entry.copyTo(target, overwrite = false) }
        }
    }

    /** Scan only SAF metadata in the background; do not copy every installed mod just to list it. */
    private fun refreshSharedModCatalogAsync() {
        if (sharedModCatalogLoading) return
        val generation = ++sharedModScanGeneration
        sharedModCatalogLoading = true
        Thread({
            val result = runCatching { SharedDataStorage(this).listModFolders() }
            runOnUiThread {
                if (generation != sharedModScanGeneration || isFinishing || isDestroyed) return@runOnUiThread
                sharedModCatalogLoading = false
                sharedModNames = result.getOrElse { emptyList() }
                modCatalog = readModCatalog(engineDataDirectory())
                renderPage()
            }
        }, "ironharbor-mod-catalog-scan").start()
    }
    private fun applyAresSetting(setting: String) {
        if (modCatalog.selectedMod == null) return
        try {
            aresSetting = nativeSetAresSetting(setting, requireSharedDataDirectory().absolutePath)
            modSelectionNote = "Saved Ares $aresSetting. The engine reads it next launch; the running game was not changed."
            renderPage()
        } catch (error: Exception) {
            modSelectionNote = "Ares setting was not changed: ${error.message ?: error.javaClass.simpleName}"
            renderPage()
        }
    }

    private fun downloadOfficialAresArchive() {
        if (downloadingAres) return
        downloadingAres = true
        modImportNote = "Downloading the pinned Ares release and checking its SHA-256..."
        renderPage()
        Thread({
            val temporaryDirectory = File(cacheDir, "ares-download-${System.nanoTime()}")
            val result = runCatching {
                if (!temporaryDirectory.mkdirs()) throw IOException("Cannot create a temporary download folder")
                val temporaryFile = File(temporaryDirectory, "ares.mix")
                val connection = java.net.URL(ARES_RELEASE_URL).openConnection().apply {
                    connectTimeout = 20_000
                    readTimeout = 30_000
                }
                connection.getInputStream().use { input ->
                    temporaryFile.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                }
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(temporaryFile.readBytes())
                    .joinToString("") { "%02x".format(it) }
                if (digest != ARES_RELEASE_SHA256) throw IOException("The downloaded Ares archive failed its integrity check")
                val storage = SharedDataStorage(this)
                val root = storage.directRootPath()
                    ?: throw IOException("Grant All files access to update the shared Ares archive")
                val directory = File(root, "Mods/AresSupport").canonicalFile
                if (!directory.toPath().startsWith(root.canonicalFile.toPath())) throw IOException("Unsafe Ares folder")
                val destination = File(directory, "ares.mix")
                if (destination.isFile && java.security.MessageDigest.getInstance("SHA-256")
                        .digest(destination.readBytes()).joinToString("") { "%02x".format(it) } == ARES_RELEASE_SHA256) {
                    "Official Ares archive is already up to date."
                } else {
                    val staged = File.createTempFile(".ares-update-", ".tmp", directory)
                    try {
                        temporaryFile.inputStream().use { input ->
                            java.io.FileOutputStream(staged).use { output ->
                                input.copyTo(output)
                                output.fd.sync()
                            }
                        }
                        if (destination.exists()) {
                            if (!destination.isFile) throw IOException("Ares archive path is not a file")
                            val backup = File.createTempFile("ares-backup-", ".bak", directory)
                            destination.copyTo(backup, overwrite = true)
                        }
                        java.nio.file.Files.move(staged.toPath(), destination.toPath(),
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                        "Official Ares archive verified and installed. Previous archive retained if present. Confirm compatibility with the selected mod."
                    } finally {
                        staged.delete()
                    }
                }
            }
            temporaryDirectory.deleteRecursively()
            runOnUiThread {
                downloadingAres = false
                modImportNote = result.getOrElse { "Ares download failed: ${it.message ?: it.javaClass.simpleName}" }
                if (!isFinishing && !isDestroyed) renderPage()
            }
        }, "ironharbor-ares-download").start()
    }

    private fun showSupportPromptForBuild() {
        val buildCode = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).longVersionCode
            } else {
                @Suppress("DEPRECATION")
                val info = packageManager.getPackageInfo(packageName, 0)
                if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            }
        }.getOrNull() ?: return
        val prefs = getSharedPreferences("ironharbor_support_prompt", MODE_PRIVATE)
        if (prefs.getLong("shown_build_code", -1L) == buildCode) return

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(18))
            background = rounded(color(R.color.launcher_surface), color(R.color.launcher_border), 20)
        }
        content.addView(kicker("FIELD COMMAND UPDATE"))
        content.addView(text("Support Iron Harbor", 23, R.color.launcher_text, bold = true))
        content.addView(text(
            "If you enjoy the project, visit and support the community pages.",
            14,
            R.color.launcher_muted,
        ), margin(top = 8, bottom = 13))
        val dialog = AlertDialog.Builder(this).setView(content).setCancelable(true).create()
        fun visit(url: String) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                .onFailure {
                    Toast.makeText(this, "No app can open that page right now.", Toast.LENGTH_SHORT).show()
                }
            dialog.dismiss()
        }
        content.addView(actionButton("VISIT FACEBOOK PAGE", primary = true) {
            visit("https://www.facebook.com/gamerbry.loyalist")
        }, margin(bottom = 7))
        content.addView(actionButton("VISIT TIKTOK PAGE", primary = false) {
            visit("https://www.tiktok.com/@gamerbry5")
        }, margin(bottom = 7))
        content.addView(actionButton("MAYBE LATER", primary = false) { dialog.dismiss() })
        dialog.setOnShowListener { prefs.edit().putLong("shown_build_code", buildCode).apply() }
        dialog.show()
    }

    private fun applyModSelection(modName: String?, profile: LauncherIniProfile?) {
        if (isFinishing || isDestroyed) return
        if (!installation.ready) {
            modSelectionNote = "Locate and verify a game installation before changing the mod or INI profile."
            renderPage()
            return
        }
        if (modCatalog.configError != null) {
            modSelectionNote = "Fix the unreadable config before changing a mod or profile."
            renderPage()
            return
        }
        if (modName != null && modCatalog.installedMods.none { it == modName }) {
            modSelectionNote = "That mod folder is no longer available or accessible. No copy was made."
            refreshInstallation()
            return
        }
        val dataDirectory = try {
            requireSharedDataDirectory()
        } catch (error: Exception) {
            modSelectionNote = "Selection was not applied: ${error.message ?: error.javaClass.simpleName}"
            renderPage()
            return
        }
        val request = modSelectionGeneration.incrementAndGet()
        val operation = if (profile == null) "mod_switch" else "profile_switch"
        val requestedLabel = "${modName ?: "Vanilla"} / ${profile?.title ?: "manifest profile"}"
        applyingModSelection = true
        modSelectionNote = "Applying $requestedLabel in the background..."
        renderPage()
        launcherIo.execute {
            val started = SystemClock.elapsedRealtime()
            val timings = mutableListOf<String>()
            var stagedModMapCount: Int? = null
            var mapStageWarning: String? = null
            val selection = runCatching {
                if (request != modSelectionGeneration.get()) throw java.util.concurrent.CancellationException("Superseded selection")
                val nativeStarted = SystemClock.elapsedRealtime()
                val contents = if (profile == null) {
                    nativeConfigForMod(modName.orEmpty(), dataDirectory.absolutePath)
                } else {
                    nativeConfigForSelection(modName.orEmpty(), profile.configValue, dataDirectory.absolutePath)
                }
                timings += "native_config_and_validation_ms=${SystemClock.elapsedRealtime() - nativeStarted}"
                if (request != modSelectionGeneration.get()) throw java.util.concurrent.CancellationException("Superseded selection")
                val publishStarted = SystemClock.elapsedRealtime()
                LauncherConfigPublisher.publish(dataDirectory, contents)
                timings += "config_publish_ms=${SystemClock.elapsedRealtime() - publishStarted}"
                val preferenceStarted = SystemClock.elapsedRealtime()
                val externalPath = modName?.takeIf {
                    File(it).isAbsolute && AndroidSharedStoragePath.relativeFolderName(File(dataDirectory, "Mods"), File(it)) == null
                }
                SharedDataStorage(this).setExternalModPath(externalPath)
                timings += "launcher_preferences_ms=${SystemClock.elapsedRealtime() - preferenceStarted}"
                val mapsStarted = SystemClock.elapsedRealtime()
                val mapsSync = runCatching {
                    val mapsDirectory = File(nativeMapsDirectory(dataDirectory.absolutePath))
                    val modDirectory = modName?.let {
                        selectedModDirectory(it)
                            ?: throw IOException("The selected mod folder is no longer accessible; its map files were left unchanged.")
                    }
                    if (LauncherModMapStaging.isSynchronized(mapsDirectory, modDirectory)) {
                        null
                    } else {
                        LauncherModMapStaging.sync(mapsDirectory, modDirectory)
                    }
                }
                timings += "temporary_mod_maps_ms=${SystemClock.elapsedRealtime() - mapsStarted}"
                mapsSync.onSuccess { result ->
                    result?.let {
                        stagedModMapCount = it.activeMaps
                        if (it.cleanupPending > 0) mapStageWarning = "cleanup is pending for ${it.cleanupPending} previous map copy/copies"
                    }
                }.onFailure { error -> mapStageWarning = error.message ?: error.javaClass.simpleName }
                contents
            }
            val elapsed = SystemClock.elapsedRealtime() - started
            timings += "total_ms=$elapsed"
            val report = buildString {
                appendLine("Iron Harbor launcher selection")
                appendLine("Timestamp UTC: ${java.time.Instant.now()}")
                appendLine("Operation: $operation")
                appendLine("Requested: $requestedLabel")
                appendLine("Result: ${selection.fold({ "applied" }, { error -> if (error is java.util.concurrent.CancellationException) "superseded" else "failed: ${error.message ?: error.javaClass.simpleName}" })}")
                timings.forEach(::appendLine)
            }
            writeLauncherTimingReport(dataDirectory, "launcher-selection-latest.txt", report)
            runOnUiThread {
                if (request != modSelectionGeneration.get() || isFinishing || isDestroyed) return@runOnUiThread
                applyingModSelection = false
                selection.fold(
                    onSuccess = {
                        modSelectionNote = buildString {
                            append("Saved. Profile and mod selection completed in ${elapsed} ms. Archive indexing is deferred to game startup and runs once there.")
                            stagedModMapCount?.let { append(" $it temporary mod maps are in the unified Maps folder.") }
                            mapStageWarning?.let { append(" Mod map staging could not finish: $it") }
                        }
                        if (stagedModMapCount != null) {
                            val summary = if (stagedModMapCount == 0) {
                                "No temporary mod maps are staged. Unselecting the mod clears only managed copies."
                            } else {
                                "$stagedModMapCount temporary mod map copies are staged in the engine Maps folder. Unselecting the mod removes only unchanged managed copies."
                            }
                            modMapStagingNote = summary + (mapStageWarning?.let { " Cleanup is pending: $it" } ?: "")
                        } else if (mapStageWarning != null) {
                            modMapStagingNote = "Temporary mod-map sync failed: $mapStageWarning"
                        }
                        refreshInstallation()
                    },
                    onFailure = { error ->
                        if (error !is java.util.concurrent.CancellationException) {
                            modSelectionNote = "Selection was not applied: ${error.message ?: error.javaClass.simpleName}"
                            renderPage()
                        }
                    },
                )
            }
        }
    }

    /** Reconcile temporary maps once at launcher startup for the persisted selection. */
    private fun synchronizePersistedModMaps() {
        val dataDirectory = engineDataDirectory()
        launcherIo.execute {
            val catalog = runCatching { readModCatalog(dataDirectory) }.getOrNull() ?: return@execute
            if (catalog.configError != null || !catalog.containsSelectedMod()) return@execute
            val result = runCatching {
                val mapsDirectory = File(nativeMapsDirectory(dataDirectory.absolutePath))
                val modDirectory = catalog.selectedMod?.let { selectedModDirectory(it) }
                if (catalog.selectedMod != null && modDirectory == null) {
                    throw IOException("The saved mod folder is inaccessible; temporary maps were left untouched.")
                }
                if (LauncherModMapStaging.isSynchronized(mapsDirectory, modDirectory)) {
                    null
                } else {
                    LauncherModMapStaging.sync(mapsDirectory, modDirectory)
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                modMapStagingNote = result.fold(
                    onSuccess = {
                        if (it == null) "Temporary mod maps are already synchronized. Unselecting the mod removes only unchanged managed copies."
                        else "${it.activeMaps} temporary mod map copies are staged. Unselecting the mod removes only unchanged managed copies." +
                            if (it.cleanupPending > 0) " Cleanup pending for ${it.cleanupPending} old map file(s)." else ""
                    },
                    onFailure = { "Temporary mod-map sync failed: ${it.message ?: it.javaClass.simpleName}" },
                )
                if (page == Page.MAPS) renderPage()
            }
        }
    }

    private fun writeLauncherTimingReport(dataDirectory: File, fileName: String, contents: String) {
        runCatching {
            val logs = File(dataDirectory, "Logs")
            if (!logs.isDirectory && !logs.mkdirs()) throw IOException("Cannot create launcher log directory")
            val target = File(logs, fileName)
            val temporary = File(logs, ".$fileName.tmp")
            FileOutputStream(temporary).use { output ->
                output.write(contents.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            java.nio.file.Files.move(
                temporary.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
            Log.i("IronHarborTiming", contents)
        }.onFailure { error ->
            Log.w("IronHarborTiming", "Could not save launcher operation timings", error)
        }
    }

    private fun chooseStorageLocation() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_PICK_STORAGE)
    }

    private fun ensureSharedStorage(): Boolean {
        val storage = SharedDataStorage(this)
        if (storage.directRootPath() != null) {
            updateEngineDataRoot()
            return true
        }
        storageNote = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            "Grant All files access first. Iron Harbor will then create Documents/RA2Harbor automatically; no extra folder import is needed."
        } else {
            "The selected Documents folder is no longer accessible. Choose Documents again; existing files will be retained."
        }
        page = Page.FILES
        renderPage()
        return false
    }

    private fun chooseGameFolder() {
        if (!ensureSharedStorage()) return
        val root = Environment.getExternalStorageDirectory()
        val current = installation.configuredDirectory?.takeIf { it.isDirectory } ?: root
        LocalFolderPicker.show(
            this, root, current, "SELECT YURI'S REVENGE GAME FOLDER",
            canUse = {
                LauncherInstallation.missingRequiredArchives(
                    it,
                    modCatalog.profile.configValue,
                    allowBaseRa2Detection = true,
                ).isEmpty()
            },
            onSelected = ::importGameDirectory,
        )
    }

    private fun chooseModFolder() {
        if (!ensureSharedStorage()) return
        val root = Environment.getExternalStorageDirectory()
        val current = SharedDataStorage(this).externalModPath()?.let(::File)?.takeIf { it.isDirectory }
            ?: File(root, "Documents")
        LocalFolderPicker.show(
            this, root, current, "SELECT MOD FOLDER",
            canUse = { it.isDirectory },
            onSelected = ::importModDirectory,
        )
    }

    private fun importMod(tree: Uri) {
        val storage = SharedDataStorage(this)
        val modDirectory = storage.directPathForTree(tree)
        if (modDirectory == null) {
            modImportNote = "Choose a local mod folder on shared storage. Cloud providers cannot provide a direct path to the Rust engine."
            renderPage()
            return
        }
        importModDirectory(modDirectory)
    }

    private fun importModDirectory(modDirectory: File) {
        val storage = SharedDataStorage(this)
        val dataDirectory = storage.directRootPath()
        val modsDirectory = dataDirectory?.let { File(it, "Mods").canonicalFile }
        val relativeName = if (modsDirectory != null) {
            AndroidSharedStoragePath.relativeFolderName(modsDirectory, modDirectory.canonicalFile)
        } else null
        val selectedName = relativeName ?: runCatching { modDirectory.canonicalPath }.getOrNull()
        if (!modDirectory.isDirectory || dataDirectory == null || selectedName.isNullOrBlank()) {
            modImportNote = "Could not access the selected mod folder or Documents/RA2Harbor. Grant All files access; source mod files are never copied."
            renderPage()
            return
        }
        if (!installation.ready) {
            modImportNote = "Locate and verify the game folder before selecting a mod. No files were changed."
            renderPage()
            return
        }
        modImporting = true
        modImportNote = "Validating ${modDirectory.name} in place..."
        renderPage()
        Thread({
            val result = runCatching {
                val config = nativeConfigForMod(selectedName, dataDirectory.absolutePath)
                LauncherConfigPublisher.publish(dataDirectory, config)
                storage.setExternalModPath(if (relativeName == null) selectedName else null)
                selectedName
            }
            result.onFailure { Log.e("IronHarborImport", "Mod selection failed for ${modDirectory.name}", it) }
            runOnUiThread {
                modImporting = false
                result.onSuccess { name ->
                    modSelectionNote = "Saved. The engine accepted this mod configuration; archive-backed selection is checked again before Play."
                    modImportNote = "$name was validated and selected in place. Its source files were not copied."
                    refreshInstallation()
                }.onFailure { error ->
                    modImportNote = "Could not use the selected mod in place: ${error.message ?: error.javaClass.simpleName}"
                    renderPage()
                }
            }
        }, "vera20k-direct-mod-selection").start()
    }
    private fun openAllFilesAccessSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val appSettings = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        try {
            startActivity(appSettings)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (error: Exception) {
                storageNote = "Android could not open All files access settings: ${error.message ?: error.javaClass.simpleName}"
                renderPage()
            }
        }
    }

    private fun importGame(tree: Uri) {
        val storage = SharedDataStorage(this)
        val gameDirectory = storage.directPathForTree(tree)
        if (gameDirectory == null) {
            runCatching { contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            importing = false
            importNote = "Choose a local game folder on shared storage. Cloud providers cannot provide a direct path to the Rust engine."
            renderPage()
            return
        }
        importGameDirectory(gameDirectory)
    }

    private fun importGameDirectory(selectedDirectory: File) {
        val storage = SharedDataStorage(this)
        val gameDirectory = runCatching { selectedDirectory.canonicalFile }.getOrNull()
        val dataDirectory = storage.directRootPath()
        if (gameDirectory == null || !gameDirectory.isDirectory || dataDirectory == null) {
            importNote = "Could not access that local folder or Documents/RA2Harbor. Check All files access and try again."
            Log.e("IronHarborImport", "Game folder selection unavailable; sharedDataReady=${dataDirectory != null}")
            renderPage()
            return
        }
        importing = true
        importNote = "Verifying the selected game folder in place..."
        page = Page.FILES
        renderPage()
        Thread({
            val result = try {
                val missing = LauncherInstallation.missingRequiredArchives(
                    gameDirectory,
                    modCatalog.profile.configValue,
                    allowBaseRa2Detection = true,
                )
                if (missing.isNotEmpty()) throw IOException("Selected folder is missing ${missing.joinToString()}")
                val contents = nativeConfigForImport(gameDirectory.absolutePath, dataDirectory.absolutePath)
                LauncherConfigPublisher.publish(dataDirectory, contents)
                storage.setGameSourcePath(gameDirectory)
                updateEngineDataRoot()
                "Using ${gameDirectory.absolutePath} directly. No game files were copied or moved."
            } catch (error: Throwable) {
                Log.e("IronHarborImport", "Game folder validation/configuration failed for ${gameDirectory.name}", error)
                "Could not use the selected folder in place: ${error.message ?: error.javaClass.simpleName}"
            }
            runOnUiThread {
                importing = false
                importNote = result
                refreshInstallation()
            }
        }, "vera20k-direct-game-selection").start()
    }

    private fun launchGame() {
        if (validatingLaunch || !launchGate.begin()) return
        launchCancellationRequested = false
        validatingLaunch = true
        renderPage()
        Thread({
            val started = SystemClock.elapsedRealtime()
            val stages = mutableListOf<String>()
            var engineSelection = "not available"
            val measure: (String, () -> Unit) -> Unit = { name, action ->
                val stageStarted = SystemClock.elapsedRealtime()
                try {
                    action()
                    stages += "$name: ${SystemClock.elapsedRealtime() - stageStarted} ms (ok)"
                } catch (error: Throwable) {
                    stages += "$name: ${SystemClock.elapsedRealtime() - stageStarted} ms (failed: ${error.javaClass.simpleName})"
                    throw error
                }
            }
            val validation = runCatching {
                syncUserManagedData(measure)
                if (launchCancellationRequested) throw java.util.concurrent.CancellationException("Cancelled by player")
                measure("engine_selection_validation") {
                    engineSelection = nativeValidateCurrentSelection(
                        engineDataDirectory().absolutePath,
                    )
                }
                if (launchCancellationRequested) throw java.util.concurrent.CancellationException("Cancelled by player")
            }
            val cancelled = validation.exceptionOrNull() is java.util.concurrent.CancellationException
            val status = if (cancelled) "cancelled" else validation.exceptionOrNull()
                ?.let { "failed: ${it.message ?: it.javaClass.simpleName}" } ?: "passed"
            val trace = buildString {
                appendLine("IronHarbor launcher preflight")
                appendLine("Timestamp UTC: ${java.time.Instant.now()}")
                appendLine("Result: $status")
                appendLine("Total: ${SystemClock.elapsedRealtime() - started} ms")
                appendLine("Selected mod: ${modCatalog.selectedMod ?: "Vanilla"}")
                val selectionLabel = if (validation.isSuccess) {
                    "Effective engine selection"
                } else {
                    "Engine selection details unavailable"
                }
                appendLine("$selectionLabel: $engineSelection")
                appendLine("Game source: ${SharedDataStorage(this@LauncherActivity).gameSourceName() ?: "configured filesystem path"}")
                stages.forEach(::appendLine)
            }
            runCatching {
                val target = File(engineDataDirectory(), "Logs/launcher-preflight-latest.txt")
                val temporary = File(engineDataDirectory(), "Logs/.launcher-preflight.tmp")
                java.io.FileOutputStream(temporary).use { output ->
                    output.write(trace.toByteArray(Charsets.UTF_8))
                    output.fd.sync()
                }
                java.nio.file.Files.move(
                    temporary.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
                Log.i("IronHarborTiming", trace)
            }
            runOnUiThread {
                validatingLaunch = false
                if (isFinishing || isDestroyed) {
                    launchGate.completedWithoutLaunch()
                    return@runOnUiThread
                }
                validation.fold(
                    onSuccess = {
                        if (cancelled || launchCancellationRequested) {
                            launchGate.completedWithoutLaunch()
                            modSelectionNote = "Startup check cancelled. The game was not started."
                            renderPage()
                            return@fold
                        }
                        try {
                            gameWasLaunched = true
                            val immersiveFullscreen = getSharedPreferences(
                                ImmersiveFullscreenSettings.PREFERENCES,
                                MODE_PRIVATE,
                            ).getBoolean(
                                ImmersiveFullscreenSettings.ENABLED,
                                ImmersiveFullscreenSettings.DEFAULT_ENABLED,
                            )
                            startActivity(Intent(this, VeraActivity::class.java).apply {
                                putExtra(ImmersiveFullscreenSettings.INTENT_EXTRA, immersiveFullscreen)
                            })
                        } catch (error: Exception) {
                            gameWasLaunched = false
                            launchGate.completedWithoutLaunch()
                            Toast.makeText(this, "Could not start the game: ${error.message}", Toast.LENGTH_LONG).show()
                            renderPage()
                        }
                    },
                    onFailure = { error ->
                        launchGate.completedWithoutLaunch()
                        modSelectionNote = if (error is java.util.concurrent.CancellationException) {
                            "Startup check cancelled. The game was not started."
                        } else {
                            "Cannot launch this game/mod selection: ${error.message ?: error.javaClass.simpleName}. Choose a profile whose required roots are available."
                        }
                        refreshInstallation()
                        page = Page.MODS
                        renderPage()
                    },
                )
            }
        }, "vera20k-launch-preflight").start()
    }

    /** Check the direct filesystem roots consumed by Rust; never mirror user assets. */
    private fun syncUserManagedData(measure: (String, () -> Unit) -> Unit) {
        val storage = SharedDataStorage(this)
        measure("shared_storage_access_check") {
            val root = storage.directRootPath()
                ?: throw IOException("Grant Android All files access to use Documents/RA2Harbor. The engine reads game and mod files in place; it does not make private copies.")
            if (root.canonicalFile != engineDataDirectory().canonicalFile) {
                throw IOException("The shared data folder is not active. Return to the launcher and select Documents again.")
            }
        }
        measure("documents_layout_check") { storage.ensureLayout() }
        val gameSourcePath = storage.gameSourcePath()
        if (gameSourcePath != null) {
            measure("game_source_check") {
                if (!gameSourcePath.isDirectory || installation.configuredDirectory?.canonicalFile != gameSourcePath.canonicalFile) {
                    throw IOException("The configured game path no longer matches the selected source folder.")
                }
            }
        } else storage.gameSourceTree()?.let { gameSource ->
            measure("game_source_check") {
                val direct = storage.directPathForTree(gameSource)
                    ?: throw IOException("The selected game source is no longer accessible. Grant access or select it again.")
                if (installation.configuredDirectory?.canonicalFile != direct.canonicalFile) {
                    throw IOException("The configured game path no longer matches the selected source folder.")
                }
            }
        }
    }
    private fun migrateExistingDataToDocuments() {
        Thread({
            val storage = SharedDataStorage(this)
            val notes = if (storage.directRootPath() == null) {
                listOf("Grant All files access to use Documents/RA2Harbor directly; existing data remains in place")
            } else emptyList()
            val retainedLegacy = storage.retainedLegacyRoots()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                updateEngineDataRoot()
                val retained = retainedLegacy.takeIf { it.isNotEmpty() }
                    ?.joinToString(prefix = " Old folders left untouched: ")
                    .orEmpty()
                storageNote = if (notes.isEmpty()) "${storage.displayLocation()} is ready. Engine data now uses this folder directly; existing source data was retained.$retained"
                else "${storage.displayLocation()} is selected. ${notes.joinToString("; ")}. Existing data was not deleted.$retained"
                refreshInstallation()
            }
        }, "ironharbor-documents-migration").start()
    }

    private fun appVersion(): String {
        val versionName = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionName
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0).versionName
            }
        }.getOrNull() ?: "Android"
        return "$versionName ${getString(R.string.app_build_label)}"
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = rounded(color(R.color.launcher_surface), color(R.color.launcher_border), 18)
    }

    private fun infoRow(parent: LinearLayout, label: String, value: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, dp(8), 0, dp(3))
        }
        row.addView(text(label, 12, R.color.launcher_accent, bold = true), LinearLayout.LayoutParams(dp(126), -2))
        row.addView(text(value, 13, R.color.launcher_text), LinearLayout.LayoutParams(0, -2, 1f))
        parent.addView(row)
    }

    private fun kicker(value: String, tint: Int = R.color.launcher_accent): TextView = text(value, 10, tint, bold = true, tracking = 0.12f).apply {
        setPadding(0, 0, 0, dp(7))
    }

    private fun text(
        value: String,
        size: Int,
        tint: Int,
        bold: Boolean = false,
        tracking: Float = 0f,
    ): TextView = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color(tint))
        if (bold) typeface = Typeface.DEFAULT_BOLD
        letterSpacing = tracking
        includeFontPadding = true
        setLineSpacing(dp(2).toFloat(), 1f)
    }

    private fun iconForAction(label: String): Int {
        val key = label.lowercase(Locale.ROOT)
        return when {
            "start game" in key || key == "play" || "launch game" in key -> R.drawable.ic_ih_play
            "cancel" in key || "close" in key -> R.drawable.ic_ih_close
            "keyboard" in key || "key bind" in key -> R.drawable.ic_ih_keyboard
            "visit" in key -> R.drawable.ic_ih_external
            "diagnostic" in key || "log" in key || "report" in key -> R.drawable.ic_ih_diagnostics
            "map" in key -> R.drawable.ic_ih_maps
            "hud" in key || "touch" in key || "control" in key -> R.drawable.ic_ih_touch
            "download" in key -> R.drawable.ic_ih_download
            "game file" in key || "folder" in key || "storage" in key || "verify" in key -> R.drawable.ic_ih_files
            "selected" in key || "save" in key || "done" in key -> R.drawable.ic_ih_selected
            "mod" in key || "ares" in key || "profile" in key || "vanilla" in key -> R.drawable.ic_ih_mods
            "import" in key -> R.drawable.ic_ih_download
            "use this" in key -> R.drawable.ic_ih_selected
            "setting" in key || "zoom" in key || "camera" in key || "graphics" in key || "audio" in key || "fps" in key || "custom" in key -> R.drawable.ic_ih_settings
            "key" in key || "remap" in key -> R.drawable.ic_ih_keyboard
            else -> R.drawable.ic_ih_list
        }
    }

    private fun presentActionLabel(label: String): String = label.trim().lowercase(Locale.ROOT)
        .split(Regex("\\s+"))
        .joinToString(" ") { word ->
            when (word) {
                "yr" -> "YR"
                "ini" -> "INI"
                "fps" -> "FPS"
                "hud" -> "HUD"
                "apk" -> "APK"
                "mo" -> "MO"
                "vulkan" -> "Vulkan"
                else -> if (word.matches(Regex("c\\d+"))) word.uppercase(Locale.ROOT)
                    else word.replaceFirstChar { it.titlecase(Locale.ROOT) }
            }
        }

    private fun rendererTitle(value: String): String = when (value.lowercase(Locale.ROOT)) {
        "vulkan" -> "Vulkan"
        "opengl", "opengles", "gles" -> "OpenGL ES"
        "dx12", "directx12" -> "Unsupported stored DirectX 12 value"
        else -> "Automatic"
    }

    private fun actionButton(label: String, primary: Boolean, action: () -> Unit): TextView = TextView(this).apply {
        text = presentActionLabel(label)
        textSize = if (primary) 16f else 13f
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.012f
        gravity = Gravity.CENTER
        minHeight = dp(if (primary) 62 else 54)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        setTextColor(color(if (primary) R.color.launcher_background else R.color.launcher_text))
        compoundDrawablePadding = dp(10)
        val tint = color(if (primary) R.color.launcher_background else R.color.launcher_accent)
        val icon = getDrawable(iconForAction(label))?.mutate()?.apply { setTint(tint) }
        setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
        background = rounded(
            color(if (primary) R.color.launcher_accent else R.color.launcher_surface_alt),
            color(if (primary) R.color.launcher_accent_dark else R.color.launcher_border),
            14,
        )
        isFocusable = true
        isClickable = true
        setOnClickListener { action() }
        contentDescription = presentActionLabel(label)
    }

    private fun rounded(fill: Int, stroke: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        setCornerRadius(dp(radius).toFloat())
        setStroke(dp(1), stroke)
    }

    private fun margin(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply {
        setMargins(0, dp(top), 0, dp(bottom))
    }

    private fun color(id: Int) = getColor(id)
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
