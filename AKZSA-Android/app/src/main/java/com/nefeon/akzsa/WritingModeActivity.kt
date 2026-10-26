package com.nefeon.akzsa

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nefeon.akzsa.network.ApiResult
import com.nefeon.akzsa.network.BackendClient
import com.nefeon.akzsa.network.WritingModeRequest
import com.nefeon.akzsa.security.InstallId
import com.nefeon.akzsa.security.SecureTokenStore
import com.nefeon.akzsa.settings.AppSettings
import com.nefeon.akzsa.settings.SettingsRepository
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class WritingModeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val processTextMode = intent?.action == Intent.ACTION_PROCESS_TEXT
        val sharedTextMode = intent?.action == Intent.ACTION_SEND && intent.type == "text/plain"
        val selectedText = when {
            processTextMode -> intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
            sharedTextMode -> intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
            else -> ""
        }
        val processTextReadOnly = processTextMode &&
            intent?.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false) == true
        setContent {
            WritingModeScreen(
                initialText = selectedText,
                processTextMode = processTextMode,
                processTextReadOnly = processTextReadOnly,
                sharedTextMode = sharedTextMode,
            )
        }
    }
}

private data class WritingTemplate(val title: String, val body: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WritingModeScreen(
    initialText: String = "",
    processTextMode: Boolean = false,
    processTextReadOnly: Boolean = false,
    sharedTextMode: Boolean = false,
) {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository(context.applicationContext) }
    val settings by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val tokenStore = remember { SecureTokenStore(context) }
    val prefs = remember { context.getSharedPreferences("akzsa_writing_mode", android.content.Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var original by remember(initialText) { mutableStateOf(initialText) }
    var result by remember { mutableStateOf("") }
    var instruction by remember { mutableStateOf("") }
    var targetLanguage by remember { mutableStateOf("Spanish") }
    var tone by remember { mutableStateOf(prefs.getString("tone", "Friendly") ?: "Friendly") }
    var preferredLanguage by remember { mutableStateOf(prefs.getString("language", "English") ?: "English") }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var explanation by remember { mutableStateOf("") }
    var replies by remember { mutableStateOf(listOf<String>()) }
    var selectedReply by remember { mutableStateOf<String?>(null) }
    var templateTitle by remember { mutableStateOf("") }
    var templates by remember { mutableStateOf(loadTemplates(prefs)) }
    var showTemplates by remember { mutableStateOf(false) }
    var lastAction by remember { mutableStateOf("fix_grammar") }
    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { outcome ->
        if (outcome.resultCode == Activity.RESULT_OK) {
            val spoken = outcome.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) instruction = spoken
        }
    }

    fun runAction(action: String, text: String = original, extraInstruction: String? = null, language: String? = null) {
        if (text.isBlank()) { status = "Enter or paste some text first."; return }
        if (loading) return
        lastAction = action
        loading = true
        status = "Generating preview…"
        // Clear any previous result so a failed request cannot leave stale text looking current.
        result = ""
        explanation = ""
        replies = emptyList()
        selectedReply = null
        scope.launch {
            val apiResult = BackendClient.generateWriting(
                settings.backendBaseUrl,
                runCatching { tokenStore.readToken() }.getOrNull(),
                WritingModeRequest(
                    action = action,
                    text = text.take(10000),
                    instruction = extraInstruction?.take(1000),
                    language = language,
                    tone = tone,
                    output_language = language ?: preferredLanguage,
                ),
                InstallId.get(context),
            )
            when (apiResult) {
                is ApiResult.Success -> {
                    result = apiResult.value.text
                    replies = apiResult.value.suggestions
                    explanation = apiResult.value.explanation
                    status = if (action == "reply_suggestions") "Choose one reply below." else "Review the preview before applying."
                }
                is ApiResult.Failure -> status = apiResult.message
            }
            loading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text("Writing Mode"); Text("AI writing tools · review before applying", style = MaterialTheme.typography.labelSmall) } },
                navigationIcon = { TextButton(onClick = { (context as? Activity)?.finish() }) { Text("Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
        ) {
            item {
                if (processTextMode || sharedTextMode) {
                    Text(
                        when {
                            processTextReadOnly -> "Selected text opened read-only; results can be copied."
                            processTextMode -> "Selected text received from the Android text-selection menu."
                            else -> "Shared text received. Review it, then copy the reply or rewrite."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("Original text", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = original, onValueChange = { original = it },
                    modifier = Modifier.fillMaxWidth(), minLines = 4,
                    label = { Text("Original text or incoming message") },
                    placeholder = { Text("Paste or type the text you want to improve…") },
                )
            }
            item {
                Text("One-tap writing tools", style = MaterialTheme.typography.titleMedium)
                val tools = listOf(
                    "Fix grammar" to "fix_grammar", "Professional" to "professional",
                    "Friendly" to "friendly", "Shorter" to "shorter", "Longer" to "longer",
                )
                tools.chunked(2).forEach { rowTools ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        rowTools.forEach { (label, action) ->
                            OutlinedButton(onClick = { runAction(action) }, modifier = Modifier.weight(1f), enabled = !loading) { Text(label) }
                        }
                        if (rowTools.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(targetLanguage, { targetLanguage = it }, modifier = Modifier.weight(1f), label = { Text("Translate to") }, singleLine = true)
                    Button(onClick = { runAction("translate", language = targetLanguage.trim()) }, enabled = !loading && targetLanguage.isNotBlank()) { Text("Translate") }
                }
            }
            item {
                Text("AI reply suggestions", style = MaterialTheme.typography.titleMedium)
                Button(onClick = { runAction("reply_suggestions") }, enabled = !loading && original.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text("Generate 3 replies")
                }
            }
            if (replies.isNotEmpty()) {
                items(replies) { reply ->
                    Card(
                        onClick = { selectedReply = reply },
                        colors = CardDefaults.cardColors(containerColor = if (selectedReply == reply) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
                    ) { Text(reply, Modifier.fillMaxWidth().padding(14.dp)) }
                }
                item {
                    Button(onClick = {
                        selectedReply?.let { result = it; explanation = "Selected reply"; status = "Selected reply is ready to copy." }
                    }, enabled = selectedReply != null, modifier = Modifier.fillMaxWidth()) { Text("Use selected reply in preview") }
                }
            }
            item {
                Text("Natural voice editing", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = instruction, onValueChange = { instruction = it },
                    modifier = Modifier.fillMaxWidth(), label = { Text("Editing instruction") },
                    placeholder = { Text("e.g. Replace college with office") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say your editing instruction")
                        }
                        runCatching { speechLauncher.launch(intent) }.onFailure { status = "Speech recognition is not available on this device." }
                    }, modifier = Modifier.weight(1f)) { Text("Speak instruction") }
                    Button(onClick = { runAction("voice_edit", extraInstruction = instruction) }, enabled = !loading && original.isNotBlank() && instruction.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Preview edit") }
                }
            }
            item {
                Text("Before and after", style = MaterialTheme.typography.titleMedium)
                if (explanation.isNotBlank()) Text(explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = result, onValueChange = { result = it }, modifier = Modifier.fillMaxWidth(), minLines = 4, label = { Text("Improved text (editable)") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = {
                        if (processTextMode && !processTextReadOnly) {
                            (context as? Activity)?.setResult(
                                Activity.RESULT_OK,
                                Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, result),
                            )
                            (context as? Activity)?.finish()
                        } else {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("AKZSA writing result", result))
                            status = if (processTextReadOnly) "Result copied. This field does not allow replacing selected text." else "Preview copied to clipboard. Paste it into your message when ready."
                        }
                    }, enabled = result.isNotBlank(), modifier = Modifier.weight(1f)) {
                        Text(if (processTextMode && !processTextReadOnly) "Apply to selected text" else "Apply / Copy")
                    }
                    OutlinedButton(onClick = {
                        result = ""; replies = emptyList(); selectedReply = null
                        status = "Preview cancelled. Original text was not changed."
                    }, modifier = Modifier.weight(1f)) { Text("Cancel preview") }
                    OutlinedButton(onClick = {
                        result = original
                        explanation = "Original text restored."
                        status = "Original text restored in the preview. Apply / Copy to use it."
                    }, enabled = original.isNotBlank() && !loading, modifier = Modifier.weight(1f)) { Text("Restore original") }
                    OutlinedButton(onClick = {
                        runAction(lastAction, original, instruction.takeIf { lastAction == "voice_edit" }, targetLanguage.takeIf { lastAction == "translate" })
                    }, enabled = !loading && original.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Try again") }
                }
                if (status.isNotBlank()) Text(status, color = if (status.contains("failed", true) || status.contains("could not", true) || status.contains("error", true)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            item {
                Text("Saved templates", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(value = templateTitle, onValueChange = { templateTitle = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Template name") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = {
                        if (templateTitle.isNotBlank() && original.isNotBlank()) {
                            templates = (templates.filterNot { it.title.equals(templateTitle.trim(), true) } + WritingTemplate(templateTitle.trim(), original)).take(100)
                            saveTemplates(prefs, templates)
                            templateTitle = ""
                            status = "Template saved locally."
                        } else status = "Enter a template name and message."
                    }, modifier = Modifier.weight(1f)) { Text("Save current text") }
                    OutlinedButton(onClick = { showTemplates = !showTemplates }, modifier = Modifier.weight(1f)) { Text(if (showTemplates) "Hide templates" else "Show templates (${templates.size})") }
                }
            }
            if (showTemplates) {
                items(templates, key = { it.title }) { template ->
                    Card {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(template.title, style = MaterialTheme.typography.titleSmall)
                            Text(template.body.take(180), style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { templateTitle = template.title; original = template.body; status = "Template loaded; edit it above before use." }) { Text("Load / edit") }
                                TextButton(onClick = { templates = templates.filterNot { it.title == template.title }; saveTemplates(prefs, templates); status = "Template deleted." }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
            item {
                Text("Personal writing style", style = MaterialTheme.typography.titleMedium)
                Text("These preferences are stored locally and attached to writing requests.", style = MaterialTheme.typography.bodySmall)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                ) {
                    listOf("Friendly", "Formal", "Concise", "Simple English").forEach { option ->
                        FilterChip(selected = tone == option, onClick = {
                            tone = option; prefs.edit().putString("tone", tone).apply()
                        }, label = { Text(option) })
                    }
                }
                OutlinedTextField(value = preferredLanguage, onValueChange = {
                    preferredLanguage = it; prefs.edit().putString("language", it).apply()
                }, modifier = Modifier.fillMaxWidth(), label = { Text("Preferred output language") }, singleLine = true)
                TextButton(onClick = {
                    tone = "Friendly"; preferredLanguage = "English"
                    prefs.edit().putString("tone", tone).putString("language", preferredLanguage).apply()
                    status = "Writing style reset."
                }) { Text("Reset style") }
            }
        }
    }
}

private fun loadTemplates(prefs: android.content.SharedPreferences): List<WritingTemplate> = runCatching {
    val array = JSONArray(prefs.getString("templates", "[]") ?: "[]")
    (0 until array.length()).mapNotNull { i ->
        val item = array.optJSONObject(i) ?: return@mapNotNull null
        val title = item.optString("title").trim()
        val body = item.optString("body")
        if (title.isBlank() || body.isBlank()) null else WritingTemplate(title, body)
    }
}.getOrDefault(emptyList())

private fun saveTemplates(prefs: android.content.SharedPreferences, templates: List<WritingTemplate>) {
    val array = JSONArray()
    templates.forEach { array.put(JSONObject().put("title", it.title).put("body", it.body)) }
    prefs.edit().putString("templates", array.toString()).apply()
}
