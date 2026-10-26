package com.nefeon.akzsa.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nefeon.akzsa.MainActivity
import com.nefeon.akzsa.diagnostics.DiagnosticStore
import com.nefeon.akzsa.keyboard.AkzsaInputMethodService
import com.nefeon.akzsa.network.ApiResult
import com.nefeon.akzsa.network.BackendClient
import androidx.compose.ui.graphics.luminance
import com.nefeon.akzsa.design.GraphiteSignal
import com.nefeon.akzsa.network.SettingsValidation
import com.nefeon.akzsa.security.SecureTokenStore
import com.nefeon.akzsa.settings.AppSettings
import com.nefeon.akzsa.settings.SettingsRepository
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Green used for "good" states, taken from the Graphite Signal tokens (AA contrast in both themes). */
private val androidx.compose.material3.ColorScheme.successTone: Color
    get() = Color(GraphiteSignal.palette(background.luminance() < 0.5f).success)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AkzsaApp(requestMicPermission: Boolean) {
    val context = LocalContext.current
    val repository = remember { SettingsRepository(context.applicationContext) }
    val settings by repository.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    var screen by remember { mutableStateOf(if (settings.onboardingComplete) "home" else "onboarding") }
    var showPermissionDialog by remember { mutableStateOf(requestMicPermission) }
    var permissionGranted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(settings.onboardingComplete) {
        if (!settings.onboardingComplete) screen = "onboarding" else if (screen == "onboarding") screen = "home"
    }
    LaunchedEffect(requestMicPermission) { if (requestMicPermission) showPermissionDialog = true }

    AkzsaTheme(settings.theme) {
        Scaffold(
            contentWindowInsets = WindowInsets.systemBars,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                if (screen != "onboarding") {
                    TopAppBar(
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.primary),
                                    contentAlignment = Alignment.Center,
                                ) { Icon(Icons.Rounded.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(21.dp)) }
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text("AKZSA", fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, fontSize = 17.sp)
                                    Text("Voice-first keyboard", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        },
                        navigationIcon = {
                            if (screen != "home") TextButton(onClick = { screen = "home" }) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Back") }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    )
                }
            },
        ) { padding ->
            when (screen) {
                "onboarding" -> OnboardingScreen(
                    modifier = Modifier.padding(padding),
                    onContinue = {
                        scope.launch { repository.update { it.copy(onboardingComplete = true) } }
                        screen = "home"
                    },
                    onPrivacy = { screen = "privacy" },
                )
                "settings" -> SettingsScreen(
                    modifier = Modifier.padding(padding), settings = settings, repository = repository,
                    onMessage = { scope.launch { snackbar.showSnackbar(it) } },
                )
                "diagnostics" -> DiagnosticsScreen(Modifier.padding(padding), settings.backendBaseUrl)
                "privacy" -> PrivacyScreen(Modifier.padding(padding))
                "help" -> HelpScreen(Modifier.padding(padding), onOpenSettings = { screen = "settings" })
                else -> HomeScreen(
                    modifier = Modifier.padding(padding), settings = settings, permissionGranted = permissionGranted,
                    onGrantPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    onEnableKeyboard = { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
                    onChooseKeyboard = {
                        val imm = context.getSystemService(InputMethodManager::class.java)
                        imm?.showInputMethodPicker()
                    },
                    onSettings = { screen = "settings" },
                    onWritingMode = { context.startActivity(Intent(context, com.nefeon.akzsa.WritingModeActivity::class.java)) },
                    onDiagnostics = { screen = "diagnostics" },
                    onPrivacy = { screen = "privacy" },
                    onHelp = { screen = "help" },
                    onMessage = { scope.launch { snackbar.showSnackbar(it) } },
                )
            }
        }
        if (showPermissionDialog) {
            AlertDialog(
                onDismissRequest = { showPermissionDialog = false },
                icon = { Icon(Icons.Rounded.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary) },
                title = { Text("Allow voice typing") },
                text = { Text("AKZSA uses Android's speech-recognition service for each dictation session. Depending on your device and provider, speech may be processed using network services. Audio is not uploaded to the AKZSA backend by this app. You can still use the keyboard without granting microphone access.") },
                confirmButton = {
                    Button(onClick = {
                        showPermissionDialog = false
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }) { Text("Continue to permission") }
                },
                dismissButton = { TextButton(onClick = { showPermissionDialog = false }) { Text("Not now") } },
            )
        }
    }
}

@Composable
private fun AkzsaTheme(theme: String, content: @Composable () -> Unit) {
    val dark = when (theme) {
        "dark" -> true
        "light" -> false
        else -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    // Graphite Signal. Lime (primary) is a FILL with dark onPrimary; `tertiary` is the accent for icons/text on
    // the page background (dark olive on Ivory, lime on Graphite) because lime alone is only 1.2:1 on Ivory.
    val p = GraphiteSignal.palette(dark)
    val colors = if (dark) darkColorScheme(
        primary = Color(p.accent), onPrimary = Color(p.onAccent), primaryContainer = Color(p.accentPressed), onPrimaryContainer = Color(p.onAccent),
        secondary = Color(p.accent), onSecondary = Color(p.onAccent), tertiary = Color(p.focus),
        background = Color(p.background), onBackground = Color(p.textPrimary),
        surface = Color(p.surface), onSurface = Color(p.textPrimary),
        surfaceVariant = Color(p.secondarySurface), onSurfaceVariant = Color(p.textSecondary),
        outline = Color(p.borderStrong), outlineVariant = Color(p.border), error = Color(p.error), onError = Color(p.onError),
    ) else lightColorScheme(
        primary = Color(p.accent), onPrimary = Color(p.onAccent), primaryContainer = Color(p.accentPressed), onPrimaryContainer = Color(p.onAccent),
        secondary = Color(p.accent), onSecondary = Color(p.onAccent), tertiary = Color(p.focus),
        background = Color(p.background), onBackground = Color(p.textPrimary),
        surface = Color(p.surface), onSurface = Color(p.textPrimary),
        surfaceVariant = Color(p.secondarySurface), onSurfaceVariant = Color(p.textSecondary),
        outline = Color(p.borderStrong), outlineVariant = Color(p.border), error = Color(p.error), onError = Color(p.onError),
    )
    MaterialTheme(colorScheme = colors, typography = MaterialTheme.typography.copy(
        headlineLarge = MaterialTheme.typography.headlineLarge.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold),
        titleLarge = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold),
        bodyLarge = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.SansSerif),
    ), content = content)
}

@Composable
private fun OnboardingScreen(modifier: Modifier, onContinue: () -> Unit, onPrivacy: () -> Unit) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Box(Modifier.size(76.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(42.dp))
        }
        Text("Your thoughts,\nstraight to text.", style = MaterialTheme.typography.headlineLarge, lineHeight = 39.sp)
        Text("AKZSA is a real Android keyboard with session-based voice typing and optional AI command interpretation.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
        FeatureRow(Icons.Rounded.Keyboard, "Type in compatible apps", "Use the system keyboard for ordinary text, email, URLs, and numeric fields.")
        FeatureRow(Icons.Rounded.Mic, "Speak naturally", "Tap Talk, speak, and insert the final recognized phrase. Partial results are not inserted.")
        FeatureRow(Icons.Rounded.Tune, "Edit with AI commands", "Ask for a supported action. AKZSA validates the backend proposal and asks before destructive or text-replacement changes.")
        FeatureRow(Icons.Rounded.Shield, "You stay in control", "Speech uses Android's selected recognition service. AI commands send only the transcript by default; editor context is opt-in.")
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Before your first dictation", fontWeight = FontWeight.SemiBold)
                Text("Android will ask for microphone permission. You must also enable AKZSA in system keyboard settings and select it with the keyboard picker. Android does not allow apps to turn on a keyboard silently.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(15.dp)) {
            Text("Set up AKZSA", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp)); Icon(Icons.Rounded.ArrowForward, contentDescription = null)
        }
        TextButton(onClick = onPrivacy, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Read privacy and data-flow notes") }
    }
}

@Composable
private fun HomeScreen(
    modifier: Modifier,
    settings: AppSettings,
    permissionGranted: Boolean,
    onGrantPermission: () -> Unit,
    onEnableKeyboard: () -> Unit,
    onChooseKeyboard: () -> Unit,
    onSettings: () -> Unit,
    onWritingMode: () -> Unit,
    onDiagnostics: () -> Unit,
    onPrivacy: () -> Unit,
    onHelp: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val inputMethodManager = context.getSystemService(InputMethodManager::class.java)
    val component = remember(context) { ComponentName(context, AkzsaInputMethodService::class.java) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeTick by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) resumeTick += 1 }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val enabled = remember(component, inputMethodManager, resumeTick) {
        inputMethodManager?.enabledInputMethodList?.any { it.id == component.flattenToString() } == true
    }
    val selected = remember(component, resumeTick) {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.contains(component.className) == true
    }
    LazyColumn(modifier = modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item {
            Column(Modifier.padding(top = 12.dp, bottom = 3.dp)) {
                Text("A calmer way to type", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(5.dp))
                Text("Voice first. Editing when you ask for it.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Keyboard setup", style = MaterialTheme.typography.titleLarge)
                            Text(if (selected) "AKZSA is selected" else if (enabled) "Enabled · select it to start typing" else "Enable AKZSA in Android settings", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        }
                        StatusDot(if (selected) "Ready" else if (enabled) "Enabled" else "Setup", selected)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onEnableKeyboard, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Text("Enable keyboard") }
                        OutlinedButton(onClick = onChooseKeyboard, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Text("Choose keyboard") }
                    }
                    if (!enabled) Text("Step 1: enable AKZSA in system keyboard settings. Step 2: return here and choose AKZSA in the keyboard picker.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            StatusCard(
                icon = Icons.Rounded.Mic,
                title = "Microphone permission",
                detail = if (permissionGranted) "Permission granted on this device" else "Needed only for voice typing; ordinary keys still work",
                good = permissionGranted,
                actionLabel = if (permissionGranted) null else "Grant permission",
                onAction = onGrantPermission,
            )
        }
        item {
            StatusCard(
                icon = Icons.Rounded.Wifi,
                title = "AI backend",
                detail = "${settings.backendBaseUrl}\nRun a diagnostics check before relying on AI commands.",
                good = false,
                actionLabel = "Test connection",
                onAction = onDiagnostics,
                neutral = true,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniAction(Icons.Rounded.Mic, "Voice typing", "Open keyboard picker") { onChooseKeyboard() }
                MiniAction(Icons.Rounded.Code, "AI commands", "Review backend setup") { onDiagnostics() }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    HomeAction(Icons.Rounded.Tune, "Writing Mode", "Grammar, tone, translation, replies, templates and voice edits", onWritingMode)
                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = .6f))
                    HomeAction(Icons.Rounded.Settings, "Settings", "Backend URL, language, consent and theme", onSettings)
                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = .6f))
                    HomeAction(Icons.Rounded.PrivacyTip, "Privacy", "What stays local and what may be sent", onPrivacy)
                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = .6f))
                    HomeAction(Icons.Rounded.FolderOpen, "File AI · storage setup", "Enable shared-storage access and index accessible files once; search and results appear in the keyboard FILE panel", {
                        context.startActivity(Intent(context, com.nefeon.akzsa.files.FileAiActivity::class.java))
                    })
                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = .6f))
                    HomeAction(Icons.Rounded.HelpOutline, "Help and troubleshooting", "Keyboard setup, permission and backend errors", onHelp)
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun FeatureRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, description: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusDot(label: String, good: Boolean) {
    Row(
        modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(if (good) MaterialTheme.colorScheme.successTone else MaterialTheme.colorScheme.tertiary))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (good) MaterialTheme.colorScheme.successTone else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun StatusCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String,
    good: Boolean,
    actionLabel: String?,
    onAction: () -> Unit,
    neutral: Boolean = false,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = if (good) MaterialTheme.colorScheme.successTone else MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.width(9.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (!neutral) StatusDot(if (good) "Ready" else "Action needed", good)
            }
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (actionLabel != null) TextButton(onClick = onAction, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp, vertical = 2.dp)) {
                Text(actionLabel); Spacer(Modifier.width(5.dp)); Icon(Icons.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun RowScope.MiniAction(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.weight(1f), onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(17.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HomeAction(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 13.dp, horizontal = 0.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.Rounded.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    modifier: Modifier,
    settings: AppSettings,
    repository: SettingsRepository,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tokenStore = remember { SecureTokenStore(context) }
    var url by remember { mutableStateOf(settings.backendBaseUrl) }
    var newToken by remember { mutableStateOf("") }
    var tokenSaved by remember { mutableStateOf(runCatching { tokenStore.hasToken() }.getOrDefault(false)) }
    var language by remember { mutableStateOf(settings.recognitionLanguage) }
    var cloudDictation by remember { mutableStateOf(settings.cloudDictationEnabled) }
    var dictionaryTerms by remember { mutableStateOf(settings.dictionaryTerms) }
    var streamingDictation by remember { mutableStateOf(settings.streamingDictation) }
    var noiseReduction by remember { mutableStateOf(settings.noiseReduction) }
    var spokenPunctuation by remember { mutableStateOf(settings.spokenPunctuation) }
    var shareContext by remember { mutableStateOf(settings.shareEditorContext) }
    var commandModeDefault by remember { mutableStateOf(settings.aiCommandModeDefault) }
    var appendSpace by remember { mutableStateOf(settings.appendSpaceAfterDictation) }
    var confirmEdits by remember { mutableStateOf(settings.alwaysConfirmEdits) }
    var haptics by remember { mutableStateOf(settings.hapticFeedback) }
    var theme by remember { mutableStateOf(settings.theme) }
    var showResetDialog by remember { mutableStateOf(false) }

    LaunchedEffect(settings.backendBaseUrl) { url = settings.backendBaseUrl }
    LaunchedEffect(settings.recognitionLanguage) { language = settings.recognitionLanguage }
    LaunchedEffect(settings.cloudDictationEnabled) { cloudDictation = settings.cloudDictationEnabled }
    LaunchedEffect(settings.dictionaryTerms) { dictionaryTerms = settings.dictionaryTerms }
    LaunchedEffect(settings.streamingDictation) { streamingDictation = settings.streamingDictation }
    LaunchedEffect(settings.noiseReduction) { noiseReduction = settings.noiseReduction }
    LaunchedEffect(settings.spokenPunctuation) { spokenPunctuation = settings.spokenPunctuation }
    LaunchedEffect(settings.shareEditorContext) { shareContext = settings.shareEditorContext }
    LaunchedEffect(settings.aiCommandModeDefault) { commandModeDefault = settings.aiCommandModeDefault }
    LaunchedEffect(settings.appendSpaceAfterDictation) { appendSpace = settings.appendSpaceAfterDictation }
    LaunchedEffect(settings.alwaysConfirmEdits) { confirmEdits = settings.alwaysConfirmEdits }
    LaunchedEffect(settings.hapticFeedback) { haptics = settings.hapticFeedback }
    LaunchedEffect(settings.theme) { theme = settings.theme }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Text("Preferences", style = MaterialTheme.typography.headlineMedium)
        Text("Private test configuration stays on this device. Never put a shared production token in source code or a public APK.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionCard("Backend") {
            OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Backend base URL") }, supportingText = { Text("Production must use HTTPS. Do not add /v1/commands/interpret.") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = newToken, onValueChange = { newToken = it }, label = { Text("Bearer token for private testing") }, placeholder = { Text(if (tokenSaved) "Saved token is encrypted · leave blank to keep" else "Paste your test token") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(if (tokenSaved) "A token is stored in Android Keystore-backed encrypted storage." else "No bearer token saved. AI command calls may be rejected by a protected backend.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    try { tokenStore.saveToken(null); tokenSaved = false; newToken = ""; onMessage("Saved token cleared") }
                    catch (_: Exception) { onMessage("Could not clear token storage") }
                }) { Text("Clear token") }
                OutlinedButton(onClick = { showResetDialog = true }) { Text("Reset settings") }
            }
        }
        SectionCard("Voice and editing") {
            Text("Recognition language", fontWeight = FontWeight.SemiBold)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    "en-IN" to "English (India)",
                    "hi-IN" to "Hindi (India)",
                    "kn-IN" to "Kannada (India)",
                    "hinglish" to "Hinglish (Latin)",
                    "en-US" to "English (US)",
                ).chunked(2).forEach { languageRow ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        languageRow.forEach { (value, label) ->
                            FilterChip(selected = language == value, onClick = { language = value }, label = { Text(label) })
                        }
                    }
                }
            }
            SettingToggle("Higher-accuracy cloud dictation (Whisper + AI cleanup)", "OFF by default. When enabled, AKZSA temporarily records the dictation, sends audio to your AKZSA backend, and the backend forwards it to Groq for transcription and optional cleanup. Requires internet and may incur provider usage charges. Kannada uses Kannada transcription; Hinglish uses mixed-language auto-detection and aims to preserve Latin script. For best Hinglish results, enable cloud dictation.", cloudDictation) { cloudDictation = it }
            OutlinedTextField(
                value = dictionaryTerms,
                onValueChange = { dictionaryTerms = it.take(4000) },
                label = { Text("Personal dictionary") },
                supportingText = { Text("One entry per line or comma. A plain word (NEFEON, Groq, a name) is used as a recognition hint and always written with your spelling and capitals. To fix a word that is often heard wrongly write: wrong = Right, for example nefion = NEFEON or kar na = karna. Device mode uses supported local hints; cloud mode sends only the correct spellings to your backend/Groq.") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 8,
            )
            SettingToggle("Live cloud dictation", "ON by default when cloud dictation is enabled. Text is inserted phrase by phrase while you keep talking (after each pause). Turn off to record everything and upload once when you tap Stop.", streamingDictation) { streamingDictation = it }
            SettingToggle("Noise reduction", "Cloud dictation only: removes low rumble, uses the phone's noise suppressor when available, ignores short bursts of noise and lifts quiet speech. Turn off if your voice sounds cut or muffled on this phone.", noiseReduction) { noiseReduction = it }
            SettingToggle("Spoken punctuation", "Say \"comma\", \"full stop\", \"question mark\", \"new line\" or \"new paragraph\" (Hindi and Hinglish phrases work too) to insert the symbol instead of the words.", spokenPunctuation) { spokenPunctuation = it }
            SettingToggle("Start in AI command mode", "The keyboard remembers this mode. You can still switch modes from the keyboard toolbar.", commandModeDefault) { commandModeDefault = it }
            SettingToggle("Add a space after dictated text", "Adds one trailing space to final dictation in normal text fields. Email and URL fields are excluded.", appendSpace) { appendSpace = it }
            SettingToggle("Share selected and surrounding text", "Off by default. When enabled, AI command requests may include the current selection and up to 500 characters before and after the cursor.", shareContext) { shareContext = it }
            SettingToggle("Confirm AI edits", "Destructive deletion and text replacement always require a review, even if this is switched off.", confirmEdits) { confirmEdits = it }
            SettingToggle("Haptic feedback", "Use Android keyboard tap feedback where available.", haptics) { haptics = it }
        }
        SectionCard("Appearance") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (value, label) ->
                    FilterChip(selected = theme == value, onClick = { theme = value }, label = { Text(label) })
                }
            }
        }
        Button(
            onClick = {
                val check = SettingsValidation.validateBackendBaseUrl(url)
                if (!check.valid || check.normalized == null) {
                    onMessage(check.message)
                } else if (newToken.isNotBlank() && newToken.trim().length > 4096) {
                    onMessage("Token is too long")
                } else {
                    scope.launch {
                        try {
                            repository.update { it.copy(
                                backendBaseUrl = check.normalized,
                                recognitionLanguage = language,
                                cloudDictationEnabled = cloudDictation,
                                dictionaryTerms = dictionaryTerms.trim(),
                                streamingDictation = streamingDictation,
                                noiseReduction = noiseReduction,
                                spokenPunctuation = spokenPunctuation,
                                aiCommandModeDefault = commandModeDefault,
                                appendSpaceAfterDictation = appendSpace,
                                shareEditorContext = shareContext,
                                alwaysConfirmEdits = confirmEdits,
                                hapticFeedback = haptics,
                                theme = theme,
                            ) }
                            if (newToken.isNotBlank()) tokenStore.saveToken(newToken.trim())
                            tokenSaved = tokenStore.hasToken()
                            newToken = ""
                            onMessage("Settings saved")
                        } catch (_: Exception) {
                            onMessage("Settings could not be saved. Check Android storage and try again.")
                        }
                    }
                }
            }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
        ) { Text("Save settings") }
        Text("The Bearer-token field is for private development only. A public release needs individual user authentication and short-lived tokens from a secure backend.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
    }
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset preferences?") },
            text = { Text("This restores local preferences to defaults. The saved bearer token is kept unless you clear it separately.") },
            confirmButton = { TextButton(onClick = { scope.launch { repository.reset(); showResetDialog = false; onMessage("Preferences reset") } }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { showResetDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
private fun SettingToggle(title: String, description: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun DiagnosticsScreen(modifier: Modifier, baseUrl: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { DiagnosticStore(context) }
    var running by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var latency by remember { mutableStateOf<Long?>(null) }
    var serverVersion by remember { mutableStateOf<String?>(null) }
    var authConfigured by remember { mutableStateOf<Boolean?>(null) }
    var voiceConfigured by remember { mutableStateOf<Boolean?>(null) }
    var lastSuccess by remember { mutableStateOf(store.snapshot().lastSuccessEpochMs) }
    var authState by remember { mutableStateOf(store.snapshot().authStatus) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Backend diagnostics", style = MaterialTheme.typography.headlineMedium)
        Text("This uses the backend's existing public GET /health route. It does not send a transcript or validate the Bearer token.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        SectionCard("Connection") {
            Text("Configured URL", fontWeight = FontWeight.SemiBold)
            Text(baseUrl, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = {
                running = true; outcome = null
                scope.launch {
                    when (val result = BackendClient.checkHealth(baseUrl)) {
                        is ApiResult.Success -> {
                            outcome = "Backend responded successfully"
                            status = "HTTP ${result.statusCode} · ${result.value.status}"
                            latency = result.latencyMs
                            serverVersion = "${result.value.service} ${result.value.version}"
                            authConfigured = result.value.authentication_configured
                            voiceConfigured = result.value.voice_transcription_configured
                            store.recordBackendSuccess(result.statusCode, result.latencyMs)
                        }
                        is ApiResult.Failure -> {
                            outcome = result.message
                            status = result.statusCode?.let { "HTTP $it" } ?: "Network / URL error"
                            latency = result.latencyMs
                            store.recordBackendFailure(result.statusCode, result.latencyMs)
                        }
                    }
                    val snap = store.snapshot(); lastSuccess = snap.lastSuccessEpochMs; authState = snap.authStatus
                    running = false
                }
            }, enabled = !running, modifier = Modifier.fillMaxWidth()) {
                if (running) { CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(8.dp)); Text("Checking…") }
                else { Icon(Icons.Rounded.Refresh, contentDescription = null); Spacer(Modifier.width(7.dp)); Text("Test connection") }
            }
            outcome?.let { Text(it, color = if (status?.startsWith("HTTP 2") == true) MaterialTheme.colorScheme.successTone else MaterialTheme.colorScheme.error) }
            DiagnosticRow("Network / backend", outcome ?: "Not checked in this session")
            DiagnosticRow("HTTP status", status ?: "—")
            DiagnosticRow("Request latency", latency?.let { "$it ms" } ?: "—")
            DiagnosticRow("Server", serverVersion ?: "—")
            DiagnosticRow("Server auth configured", authConfigured?.let { if (it) "Yes" else "No" } ?: "—")
            DiagnosticRow("Cloud voice configured", voiceConfigured?.let { if (it) "Yes" else "No" } ?: "—")
            DiagnosticRow("Client token check", authState.replaceFirstChar { it.uppercase() } + " (health cannot verify token)")
            DiagnosticRow("Last successful request", if (lastSuccess > 0L) formatTime(lastSuccess) else "None recorded")
        }
        SectionCard("How to interpret this") {
            Text("A successful health check confirms only that /health answered. Cloud voice configuration indicates whether a Groq API key is present; it does not test transcription quality or validate your token.", style = MaterialTheme.typography.bodyMedium)
            Text("To verify authentication end-to-end, select AKZSA in a normal text field, save your private testing token in Settings, enable AI command mode, and speak a harmless command. HTTP 401/403 and server/provider errors will be reported in the keyboard.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(1.15f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PrivacyScreen(modifier: Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Privacy and data flow", style = MaterialTheme.typography.headlineMedium)
        SectionCard("Voice dictation") {
            Text("With Higher-accuracy cloud dictation OFF, AKZSA uses Android SpeechRecognizer. The selected recognition service may process audio on-device or through its own network service depending on the device, language pack, and provider.")
            Text("If you explicitly turn Higher-accuracy cloud dictation ON in Settings, AKZSA records a short session to a temporary file on your phone, uploads the audio to your configured AKZSA backend at /v1/voice/transcribe, and the backend forwards the audio to Groq Whisper for transcription. The backend may also send the transcript to the Groq chat model to add punctuation, remove filler words, and resolve spoken self-corrections. The AKZSA server does not intentionally save the uploaded audio to disk; the Android temporary file is deleted after the request. This is cloud processing, not offline dictation.")
            Text("Do not dictate passwords, payment details, medical information, or confidential text in cloud mode. The backend and provider still process the audio/transcript to fulfil the request. Turn the feature OFF to return to Android's selected speech-recognition service.")
        }
        SectionCard("File AI") {
            Text("File AI search is built into the keyboard. Use this screen only for one-time shared-storage permission and indexing. OCR, text extraction, the complete index and result ranking run locally on the phone for supported PDFs, images, text and common Office formats. The index and OCR text are never uploaded to the AKZSA backend. FILE-mode speech uses Android's selected SpeechRecognizer provider, which may use that provider's network service depending on device and settings; the FILE query and file index are not sent to the AKZSA backend. The index is stored in AKZSA's app-private database; use storage setup to clear it.")
        }
        SectionCard("AI command mode") {
            Text("When you use AI command mode, AKZSA sends the recognized transcript to the configured backend with mode=command. It does not send commands in the background.")
            Text("Selected text and nearby text are not read or included unless you explicitly enable ‘Share selected and surrounding text’ in Settings. If enabled, requests may include the selected text and up to 500 characters before and after the cursor.")
            Text("The backend sends command data to the configured AI provider (the supplied backend uses Groq). Review your backend/provider settings before entering private or sensitive information.")
        }
        SectionCard("Local storage and limitations") {
            Text("Preferences use Android DataStore. A private testing bearer token is encrypted using an Android Keystore-backed AES-GCM key. Clearing app data or losing an invalidated key may require entering the token again.")
            Text("AKZSA does not request contacts, location, SMS, call logs, or analytics permissions. This screen is a data-flow summary, not a legal privacy policy or a claim of regulatory compliance. Publish a reviewed privacy policy before public distribution.")
        }
    }
}

@Composable
private fun HelpScreen(modifier: Modifier, onOpenSettings: () -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Help and troubleshooting", style = MaterialTheme.typography.headlineMedium)
        SectionCard("Enable the keyboard") {
            Text("1. Tap Enable keyboard on Home and switch on AKZSA in Android's Input method settings.\n2. Return to AKZSA and tap Choose keyboard.\n3. Pick AKZSA in the system picker.\n4. Open a Notes app or a browser search field and tap the text field.")
        }
        SectionCard("Voice typing") {
            Text("Grant microphone permission from AKZSA first. Confirm your phone has a speech-recognition service installed and selected. Recognition may require network access. Partial text is only shown in the keyboard status; only the final result is inserted once.")
        }
        SectionCard("AI commands and server errors") {
            Text("AI commands require a reachable backend, a valid test token for protected deployments, and a configured AI provider. Open Diagnostics to test /health, then Settings to check the URL and token. A successful health response alone does not validate the token or AI provider.")
            Text("The app deliberately rejects unknown actions. File-search commands open the keyboard FILE panel and search the accessible shared-storage files indexed on this device. The storage setup screen is used only to grant Android file access and build the local index. Text rewriting requires the backend to return actual replacement text.")
        }
        SectionCard("Password and sensitive fields") {
            Text("AKZSA disables voice and AI commands in password fields. Do not use voice dictation to enter passwords, payment credentials, or other sensitive secrets.")
        }
        Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Settings, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Open settings") }
    }
}

private fun formatTime(epochMs: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))
