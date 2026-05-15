package com.curiokid.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.curiokid.app.R
import com.curiokid.app.ai.provider.LlmProvider
import com.curiokid.app.data.debug.DebugLog
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val provider by viewModel.provider.collectAsState()
    val googleKey by viewModel.googleApiKey.collectAsState()
    val openRouterKey by viewModel.openRouterApiKey.collectAsState()
    val googleModel by viewModel.googleModel.collectAsState()
    val openRouterModel by viewModel.openRouterModel.collectAsState()
    val localModel by viewModel.localModel.collectAsState()
    val pin by viewModel.parentPin.collectAsState()
    val debugMode by viewModel.debugMode.collectAsState()
    val debugEntries by viewModel.debugEntries.collectAsState()
    val kidAge by viewModel.kidAge.collectAsState()

    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copiedMessage = stringResource(R.string.copied_to_clipboard)
    val emptyLogMessage = stringResource(R.string.debug_log_empty)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                modifier = Modifier.statusBarsPadding(),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ProviderCard(
                provider = provider,
                onSelect = viewModel::setProvider,
            )

            when (provider) {
                LlmProvider.GOOGLE_AI_STUDIO -> ApiKeyCard(
                    title = stringResource(R.string.settings_google_api_key),
                    description = "Stored encrypted on this device. Get a key at aistudio.google.com.",
                    storedKey = googleKey,
                    onSave = viewModel::saveGoogleApiKey,
                    onClear = viewModel::clearGoogleApiKey,
                )

                LlmProvider.OPEN_ROUTER -> ApiKeyCard(
                    title = stringResource(R.string.settings_openrouter_api_key),
                    description = "Stored encrypted on this device. Get a key at openrouter.ai/keys.",
                    storedKey = openRouterKey,
                    onSave = viewModel::saveOpenRouterApiKey,
                    onClear = viewModel::clearOpenRouterApiKey,
                )

                LlmProvider.LOCAL -> LocalModelCard()
            }

            ModelCard(
                provider = provider,
                models = viewModel.modelsFor(provider),
                selected = when (provider) {
                    LlmProvider.GOOGLE_AI_STUDIO -> googleModel
                    LlmProvider.OPEN_ROUTER -> openRouterModel
                    LlmProvider.LOCAL -> localModel
                },
                onSelect = { value ->
                    when (provider) {
                        LlmProvider.GOOGLE_AI_STUDIO -> viewModel.setGoogleModel(value)
                        LlmProvider.OPEN_ROUTER -> viewModel.setOpenRouterModel(value)
                        LlmProvider.LOCAL -> viewModel.setLocalModel(value)
                    }
                },
            )

            KidAgeCard(
                selected = kidAge,
                options = viewModel.kidAgeOptions,
                onSelect = viewModel::setKidAge,
            )

            PinCard(
                pin = pin,
                onSave = viewModel::setPin,
            )

            DeveloperCard(
                enabled = debugMode,
                onToggle = viewModel::setDebugMode,
            )

            if (debugMode) {
                DebugLogCard(
                    entries = debugEntries,
                    onClear = viewModel::clearDebugLog,
                    onCopyAll = {
                        val text = DebugLog.formatForClipboard(debugEntries)
                        if (text.isBlank()) {
                            scope.launch { snackbarHostState.showSnackbar(emptyLogMessage) }
                        } else {
                            clipboardManager.setText(AnnotatedString(text))
                            scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                        }
                    },
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "Curio Kid stores your API keys only on this device. Each key is sent only to the matching provider you've selected (Google AI Studio or OpenRouter).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ProviderCard(
    provider: LlmProvider,
    onSelect: (LlmProvider) -> Unit,
) {
    SectionCard(
        title = stringResource(R.string.settings_provider),
        description = "Pick which Gemma 4 back-end Luna talks to.",
    ) {
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LlmProvider.entries.forEach { option ->
                val selected = option == provider
                AssistChip(
                    onClick = { onSelect(option) },
                    label = { Text(option.displayName) },
                    colors = if (selected) {
                        AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            labelColor = Color.White,
                        )
                    } else AssistChipDefaults.assistChipColors(),
                )
            }
        }
    }
}

@Composable
private fun ApiKeyCard(
    title: String,
    description: String,
    storedKey: String?,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
) {
    var draft by rememberSaveable(storedKey) { mutableStateOf(storedKey.orEmpty()) }
    var visible by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(storedKey) {
        draft = storedKey.orEmpty()
    }

    SectionCard(title = title, description = description) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.trim() },
            label = { Text(stringResource(R.string.settings_api_key_hint)) },
            singleLine = true,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        imageVector = if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (visible) "Hide" else "Show",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onSave(draft) },
                enabled = draft.isNotBlank() && draft != storedKey,
            ) { Text(stringResource(R.string.settings_save)) }
            OutlinedButton(
                onClick = {
                    onClear()
                    draft = ""
                },
                enabled = !storedKey.isNullOrBlank(),
            ) { Text(stringResource(R.string.settings_clear)) }
        }
    }
}

@Composable
private fun LocalModelCard() {
    SectionCard(
        title = stringResource(R.string.settings_local_model_title),
        description = "Runs Gemma 4 fully on this device — private, no internet, no API key needed. " +
            "Setup requires downloading a Gemma 4 .task model file (a few GB). " +
            "On-device inference isn't wired up yet in this build; pick another provider for now.",
    ) {
        Text(
            text = "Status: not yet installed.",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ModelCard(
    provider: LlmProvider,
    models: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val description = when (provider) {
        LlmProvider.GOOGLE_AI_STUDIO -> "Pick which Gemma 4 model Luna uses on Google AI Studio. " +
            "26B (MoE) is fast and great for kids. 31B (dense) is more thoughtful but slower."
        LlmProvider.OPEN_ROUTER -> "Pick the Gemma 4 model on OpenRouter. " +
            "OpenRouter routes the request to a Gemma 4 host — pricing and latency vary by upstream."
        LlmProvider.LOCAL -> "Pick the on-device Gemma 4 size. " +
            "2B int4 fits on most phones; 7B int4 needs more RAM."
    }
    SectionCard(
        title = stringResource(R.string.settings_model),
        description = description,
    ) {
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            models.forEach { name ->
                val isSelected = name == selected
                AssistChip(
                    onClick = { onSelect(name) },
                    label = { Text(name) },
                    colors = if (isSelected) {
                        AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            labelColor = Color.White,
                        )
                    } else AssistChipDefaults.assistChipColors(),
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun KidAgeCard(
    selected: Int,
    options: List<Int>,
    onSelect: (Int) -> Unit,
) {
    SectionCard(
        title = stringResource(R.string.settings_kid_age),
        description = stringResource(R.string.settings_kid_age_description),
    ) {
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { age ->
                val isSelected = age == selected
                AssistChip(
                    onClick = { onSelect(age) },
                    label = { Text(age.toString()) },
                    colors = if (isSelected) {
                        AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            labelColor = Color.White,
                        )
                    } else AssistChipDefaults.assistChipColors(),
                )
            }
        }
        Text(
            text = stringResource(R.string.settings_kid_age_value, selected),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun PinCard(
    pin: String?,
    onSave: (String?) -> Unit,
) {
    var draft by rememberSaveable(pin) { mutableStateOf(pin.orEmpty()) }

    LaunchedEffect(pin) {
        draft = pin.orEmpty()
    }

    SectionCard(
        title = stringResource(R.string.settings_pin),
        description = "Used to unlock the parent dashboard.",
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { v ->
                draft = v.filter(Char::isDigit).take(4)
            },
            label = { Text("4-digit PIN") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onSave(draft) },
                enabled = draft.length == 4 && draft != pin,
            ) { Text(stringResource(R.string.settings_save)) }
            OutlinedButton(
                onClick = {
                    onSave(null)
                    draft = ""
                },
                enabled = !pin.isNullOrBlank(),
            ) { Text(stringResource(R.string.settings_clear)) }
        }
    }
}

@Composable
private fun DeveloperCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    SectionCard(
        title = stringResource(R.string.settings_developer),
        description = "Show raw error messages in chat and reveal the in-app debug log below. Helpful while building Luna; turn off before handing the phone back to a child.",
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.BugReport,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.fillMaxWidth(0.04f))
            Text(
                text = stringResource(R.string.settings_developer_toggle),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
            )
        }
    }
}

@Composable
private fun DebugLogCard(
    entries: List<DebugLog.Entry>,
    onClear: () -> Unit,
    onCopyAll: () -> Unit,
) {
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    SectionCard(
        title = stringResource(R.string.settings_debug_log),
        description = "Most recent ${entries.size} entries (oldest first). Long-press any line to select; use Copy log to grab everything for a bug report.",
    ) {
        if (entries.isEmpty()) {
            Text(
                stringResource(R.string.debug_log_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        } else {
            SelectionContainer {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp),
                        )
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    entries.takeLast(60).forEach { entry ->
                        DebugLogRow(entry = entry, timeFormatter = timeFormatter)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onCopyAll,
                enabled = entries.isNotEmpty(),
            ) { Text(stringResource(R.string.settings_copy_log)) }
            OutlinedButton(
                onClick = onClear,
                enabled = entries.isNotEmpty(),
            ) { Text(stringResource(R.string.settings_clear)) }
        }
    }
}

@Composable
private fun DebugLogRow(
    entry: DebugLog.Entry,
    timeFormatter: SimpleDateFormat,
) {
    val levelColor = when (entry.level) {
        DebugLog.Level.ERROR -> MaterialTheme.colorScheme.error
        DebugLog.Level.WARN -> MaterialTheme.colorScheme.tertiary
        DebugLog.Level.INFO -> MaterialTheme.colorScheme.primary
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row {
            Text(
                text = entry.level.name.padEnd(5),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = levelColor,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.fillMaxWidth(0.02f))
            Text(
                text = timeFormatter.format(Date(entry.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.fillMaxWidth(0.02f))
            Text(
                text = entry.tag,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            )
        }
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        )
        entry.stackTrace?.let { trace ->
            Text(
                text = trace.lineSequence().take(6).joinToString("\n"),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    description: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            content()
        }
    }
}
