package app.onion

import android.app.ActivityManager
import android.os.Build
import android.content.Context
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.onion.creation.AppCreationToolManager
import app.onion.creation.AppCreationToolModel
import app.onion.creation.AppCreationToolState
import app.onion.creation.DownloadStatus
import app.onion.generation.AppCategory
import app.onion.generation.AppGenerationManager
import app.onion.generation.AppGenerationRequest
import app.onion.generation.AppGenerationState
import app.onion.generation.AppStyle
import app.onion.generation.GenerationStatus
import app.onion.generation.GeneratedAppDraft
import app.onion.generation.LiteRtLocalLlmClient
import app.onion.generation.SavedMiniApp
import app.onion.ui.OnionTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
        setContent {
            val context = LocalContext.current
            val preferences = remember { context.getSharedPreferences("onion", Context.MODE_PRIVATE) }
            val manager = remember {
                AppCreationToolManager(
                    context = context,
                    recommendedModel = AppCreationToolModel.recommendFor(context.deviceMemoryGb()),
                )
            }
            val generationManager = remember {
                AppGenerationManager(
                    context = context,
                    preferences = preferences,
                    localLlmClient = LiteRtLocalLlmClient(context),
                )
            }
            val toolState by manager.state.collectAsState()
            val generationState by generationManager.state.collectAsState()
            var appLanguage by remember {
                mutableStateOf(preferences.enumValue("language", AppLanguage.Korean))
            }
            var themeMode by remember {
                mutableStateOf(preferences.enumValue("theme", AppThemeMode.System))
            }
            var destination by remember {
                mutableStateOf(
                    if (preferences.getBoolean("onboarding_complete", false)) {
                        AppDestination.Home
                    } else {
                        AppDestination.Onboarding
                    },
                )
            }
            var selectedApp by remember { mutableStateOf<SavedMiniApp?>(null) }
            var selectedDraftId by remember { mutableStateOf<String?>(null) }
            var editingApp by remember { mutableStateOf<SavedMiniApp?>(null) }
            val strings = remember(appLanguage) { OnionStrings.forLanguage(appLanguage) }

            OnionTheme(themeMode = themeMode) {
                BackHandler(enabled = destination == AppDestination.Preview || destination == AppDestination.Create || destination == AppDestination.Settings || destination == AppDestination.Market) {
                    generationManager.clearCurrent()
                    selectedApp = null
                    editingApp = null
                    destination = AppDestination.Home
                }

                when (destination) {
                    AppDestination.Onboarding -> OnionOnboarding(
                        strings = strings,
                        selectedLanguage = appLanguage,
                        selectedThemeMode = themeMode,
                        toolState = toolState,
                        onLanguageSelected = {
                            appLanguage = it
                            preferences.edit().putString("language", it.name).apply()
                        },
                        onThemeSelected = {
                            themeMode = it
                            preferences.edit().putString("theme", it.name).apply()
                        },
                        onDownloadTool = {
                            manager.startDownloadInBackground()
                            preferences.edit().putBoolean("onboarding_complete", true).apply()
                            destination = AppDestination.Home
                        },
                        onSkipTool = {
                            preferences.edit().putBoolean("onboarding_complete", true).apply()
                            destination = AppDestination.Home
                        },
                    )

                        AppDestination.Home -> OnionHomeScreen(
                            strings = strings,
                            toolState = toolState,
                            generationState = generationState,
                            onAddApp = {
                                editingApp = null
                                destination = AppDestination.Create
                            },
                        onOpenDraft = {
                            selectedDraftId = it.id
                            selectedApp = null
                            destination = AppDestination.Preview
                        },
                        onOpenApp = {
                            selectedApp = it
                            selectedDraftId = null
                            destination = AppDestination.Preview
                        },
                        onEditApp = {
                            editingApp = it
                            destination = AppDestination.Create
                        },
                        onDeleteApp = generationManager::deleteApp,
                            onCancelDraft = generationManager::cancelGeneration,
                        onOpenMarket = { destination = AppDestination.Market },
                        onOpenSettings = { destination = AppDestination.Settings },
                    )

                    AppDestination.Create -> AppCreateScreen(
                        editingApp = editingApp,
                        onBack = {
                            editingApp = null
                            destination = AppDestination.Home
                        },
                        onGenerate = { request ->
                            val replacingId = editingApp?.id
                            selectedApp = null
                            val draftId = generationManager.enqueue(request, replacingId)
                            selectedDraftId = draftId
                            destination = AppDestination.Preview
                        },
                    )

                    AppDestination.Preview -> AppPreviewScreen(
                        draft = selectedDraftId?.let { generationManager.draftById(it) } ?: generationState.current,
                        savedApp = selectedApp,
                        onCancelDraft = {
                            selectedDraftId?.let(generationManager::cancelGeneration)
                            selectedDraftId = null
                            destination = AppDestination.Home
                        },
                        onBack = {
                            generationManager.clearCurrent()
                            selectedApp = null
                            selectedDraftId = null
                            destination = AppDestination.Home
                        },
                    )

                    AppDestination.Settings -> SettingsScreen(
                        strings = strings,
                        selectedLanguage = appLanguage,
                        selectedThemeMode = themeMode,
                        toolState = toolState,
                        onLanguageSelected = {
                            appLanguage = it
                            preferences.edit().putString("language", it.name).apply()
                        },
                        onThemeSelected = {
                            themeMode = it
                            preferences.edit().putString("theme", it.name).apply()
                        },
                        onSelectModel = manager::selectModel,
                        onStartDownload = manager::startDownloadInBackground,
                        onBack = { destination = AppDestination.Home },
                        onOpenHome = { destination = AppDestination.Home },
                        onOpenMarket = { destination = AppDestination.Market },
                    )

                    AppDestination.Market -> MarketScreen(
                        onOpenHome = { destination = AppDestination.Home },
                        onOpenSettings = { destination = AppDestination.Settings },
                    )
                }
            }
        }
    }
}

private enum class AppDestination {
    Onboarding,
    Home,
    Create,
    Preview,
    Settings,
    Market,
}

private inline fun <reified T : Enum<T>> android.content.SharedPreferences.enumValue(
    key: String,
    fallback: T,
): T {
    val stored = getString(key, null) ?: return fallback
    return enumValues<T>().firstOrNull { it.name == stored } ?: fallback
}

private enum class AppLanguage(val label: String, val nativeLabel: String) {
    Korean("Korean", "한국어"),
    English("English", "English"),
    Japanese("Japanese", "日本語"),
    Chinese("Chinese", "中文"),
}

enum class AppThemeMode {
    System,
    Light,
    Dark,
}

private fun Context.deviceMemoryGb(): Int {
    val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memoryInfo = ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(memoryInfo)
    return (memoryInfo.totalMem / 1_073_741_824L).toInt().coerceAtLeast(1)
}

@Composable
private fun OnionOnboarding(
    strings: OnionStrings,
    selectedLanguage: AppLanguage,
    selectedThemeMode: AppThemeMode,
    toolState: AppCreationToolState,
    onLanguageSelected: (AppLanguage) -> Unit,
    onThemeSelected: (AppThemeMode) -> Unit,
    onDownloadTool: () -> Unit,
    onSkipTool: () -> Unit,
) {
    var step by remember { mutableIntStateOf(0) }
    var introPage by remember { mutableIntStateOf(0) }
    val introPages = strings.introPages

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 22.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            OnionHeader(strings = strings)

            AnimatedContent(
                targetState = step,
                label = "onboarding-step",
                modifier = Modifier.weight(1f),
            ) { currentStep ->
                when (currentStep) {
                    0 -> LanguageStep(
                        strings = strings,
                        selectedLanguage = selectedLanguage,
                        onLanguageSelected = onLanguageSelected,
                    )

                    1 -> ThemeStep(
                        strings = strings,
                        selectedThemeMode = selectedThemeMode,
                        onThemeSelected = onThemeSelected,
                    )

                    2 -> IntroStep(
                        page = introPages[introPage],
                        pageIndex = introPage,
                        pageCount = introPages.size,
                    )

                    else -> ToolChoiceStep(
                        strings = strings,
                        state = toolState,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (step == 2) {
                    PageDots(page = introPage, count = introPages.size)
                }

                Button(
                    onClick = {
                        when {
                            step < 2 -> step += 1
                            step == 2 && introPage < introPages.lastIndex -> introPage += 1
                            step == 2 -> step += 1
                            else -> onDownloadTool()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    Text(
                        text = when {
                            step < 2 -> strings.next
                            step == 2 && introPage < introPages.lastIndex -> strings.next
                            step == 2 -> strings.chooseCreationTool
                            else -> strings.downloadCreationTool
                        },
                    )
                }

                if (step == 3) {
                    OutlinedButton(
                        onClick = onSkipTool,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = strings.skipForNow)
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguageStep(
    strings: OnionStrings,
    selectedLanguage: AppLanguage,
    onLanguageSelected: (AppLanguage) -> Unit,
) {
    SelectionStepScaffold(
        title = strings.selectLanguageTitle,
        body = strings.selectLanguageBody,
    ) {
        AppLanguage.entries.forEach { language ->
            SelectableRow(
                title = language.nativeLabel,
                body = language.label,
                selected = selectedLanguage == language,
                onClick = { onLanguageSelected(language) },
            )
        }
    }
}

@Composable
private fun ThemeStep(
    strings: OnionStrings,
    selectedThemeMode: AppThemeMode,
    onThemeSelected: (AppThemeMode) -> Unit,
) {
    SelectionStepScaffold(
        title = strings.selectThemeTitle,
        body = strings.selectThemeBody,
    ) {
        AppThemeMode.entries.forEach { mode ->
            SelectableRow(
                title = strings.themeLabel(mode),
                body = strings.themeDescription(mode),
                selected = selectedThemeMode == mode,
                onClick = { onThemeSelected(mode) },
            )
        }
    }
}

@Composable
private fun SelectionStepScaffold(
    title: String,
    body: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 34.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun IntroStep(
    page: IntroPage,
    pageIndex: Int,
    pageCount: Int,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 38.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(id = R.drawable.onion_mark),
                contentDescription = null,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(18.dp)),
            )
            Spacer(modifier = Modifier.width(14.dp))
            PageDots(page = pageIndex, count = pageCount)
        }
        Text(
            text = page.title,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = page.body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ToolChoiceStep(
    strings: OnionStrings,
    state: AppCreationToolState,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 34.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = strings.toolChoiceTitle,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = strings.toolChoiceBody,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(24.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = strings.creationTool,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = strings.recommendedTool(state.selectedModel),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = strings.backgroundDownloadNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun SelectableRow(
    title: String,
    body: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontWeight = FontWeight.Bold)
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PageDots(page: Int, count: Int) {
    Row(
        horizontalArrangement = Arrangement.Start,
    ) {
        repeat(count) { index ->
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(width = if (index == page) 24.dp else 8.dp, height = 8.dp)
                    .clip(CircleShape)
                    .background(
                        if (index == page) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
            )
        }
    }
}

@Composable
private fun OnionHomeScreen(
    strings: OnionStrings,
    toolState: AppCreationToolState,
    generationState: AppGenerationState,
    onAddApp: () -> Unit,
    onOpenDraft: (GeneratedAppDraft) -> Unit,
    onOpenApp: (SavedMiniApp) -> Unit,
    onEditApp: (SavedMiniApp) -> Unit,
    onDeleteApp: (String) -> Unit,
    onCancelDraft: (String) -> Unit,
    onOpenMarket: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val activeDrafts = generationState.drafts.filter { it.status == GenerationStatus.Queued || it.status == GenerationStatus.Generating }
    val hasApps = generationState.apps.isNotEmpty() || activeDrafts.isNotEmpty()
    val canCreate = toolState.canCreateApp

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            OnionBottomBar(
                selected = AppDestination.Home,
                onOpenMarket = onOpenMarket,
                onOpenHome = {},
                onOpenSettings = onOpenSettings,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(horizontal = 22.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OnionHeader(strings = strings, modifier = Modifier.weight(1f))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = strings.myApps,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                if (hasApps) {
                    Button(
                        onClick = onAddApp,
                        enabled = canCreate,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onBackground),
                    ) {
                        Icon(painter = painterResource(id = R.drawable.ic_add), contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "앱 추가")
                    }
                }
            }

            if (hasApps) {
                activeDrafts.forEach { draft ->
                    GeneratingAppRow(
                        draft = draft,
                        onClick = { onOpenDraft(draft) },
                        onCancel = { onCancelDraft(draft.id) },
                    )
                }
                generationState.apps.forEach { app ->
                    SavedAppRow(
                        app = app,
                        onClick = { onOpenApp(app) },
                        onEdit = { onEditApp(app) },
                        editEnabled = canCreate,
                        onDelete = { onDeleteApp(app.id) },
                    )
                }
            } else {
                EmptyAppsState(
                    enabled = canCreate,
                    onAddApp = onAddApp,
                )
            }

        }
    }
}

@Composable
private fun OnionHeader(
    strings: OnionStrings,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(id = R.drawable.onion_mark),
            contentDescription = "Onion",
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp)),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = "Onion",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = strings.appTagline,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ToolSetupCard(
    strings: OnionStrings,
    state: AppCreationToolState,
    showSettings: Boolean,
    onToggleSettings: () -> Unit,
    onSelectModel: (AppCreationToolModel) -> Unit,
    onStartDownload: () -> Unit,
    showToggleButton: Boolean = true,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(status = state.downloadStatus)
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = strings.creationTool,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = strings.statusLabel(state),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (showToggleButton) {
                    OutlinedButton(onClick = onToggleSettings) {
                        Text(text = if (showSettings) strings.close else strings.advancedSettings)
                    }
                }
            }

            if (state.downloadStatus == DownloadStatus.Downloading) {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Text(
                    text = state.downloadText(strings.downloadingInBackground),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.downloadStatus == DownloadStatus.Failed && state.statusMessage != null) {
                Text(
                    text = state.statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            AnimatedVisibility(visible = showSettings) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        text = strings.modelAutoSelected,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AppCreationToolModel.entries.forEach { model ->
                        ModelRow(
                            strings = strings,
                            model = model,
                            selected = state.selectedModel == model,
                            recommended = state.recommendedModel == model,
                            enabled = state.canChangeModel,
                            onClick = { onSelectModel(model) },
                        )
                    }
                }
            }

            if (state.downloadStatus != DownloadStatus.Ready) {
                Button(
                    onClick = onStartDownload,
                    enabled = state.downloadStatus != DownloadStatus.Downloading,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = if (state.downloadStatus == DownloadStatus.Downloading) strings.preparing else strings.downloadCreationTool)
                }
            }
        }
    }
}

@Composable
private fun ModelRow(
    strings: OnionStrings,
    model: AppCreationToolModel,
    selected: Boolean,
    recommended: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
        color = Color.Transparent,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Column(modifier = Modifier.weight(1f)) {
                Text(text = model.label, fontWeight = FontWeight.SemiBold)
                Text(
                    text = strings.modelDescription(model),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.48f),
                )
            }
            if (recommended) {
                Text(
                    text = strings.recommended,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun CreationEntryCard(
    strings: OnionStrings,
    state: AppCreationToolState,
    onCreateApp: () -> Unit,
    onPrepareTool: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = strings.newApp,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = strings.newAppExamples,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
            )
            Button(
                onClick = if (state.canCreateApp) onCreateApp else onPrepareTool,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onPrimaryContainer),
            ) {
                Text(
                    text = if (state.canCreateApp) strings.describeApp else strings.prepareCreationTool,
                    color = MaterialTheme.colorScheme.primaryContainer,
                )
            }
            if (!state.canCreateApp) {
                Text(
                    text = strings.creationToolRequired,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.68f),
                )
            }
        }
    }
}

@Composable
private fun SavedAppsPlaceholder(strings: OnionStrings) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = strings.myApps,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(20.dp),
        ) {
            Text(
                text = strings.emptyApps,
                modifier = Modifier.padding(18.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyAppsState(
    enabled: Boolean,
    onAddApp: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "아직 만든 앱이 없습니다",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "첫 앱을 만들면 여기에 기록됩니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onAddApp,
                enabled = enabled,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onBackground),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(painter = painterResource(id = R.drawable.ic_add), contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "첫 앱 추가하기")
            }
            if (!enabled) {
                Text(
                    text = "앱을 만들려면 설정에서 앱 생성 도구를 먼저 준비해주세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SavedAppRow(
    app: SavedMiniApp,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    editEnabled: Boolean,
    onDelete: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = app.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = app.prompt,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onEdit,
                    enabled = editEnabled,
                ) {
                    Icon(painter = painterResource(id = R.drawable.ic_edit), contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "수정")
                }
                OutlinedButton(onClick = onDelete) {
                    Icon(painter = painterResource(id = R.drawable.ic_delete), contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "삭제")
                }
            }
        }
    }
}

@Composable
private fun GeneratingAppRow(
    draft: GeneratedAppDraft,
    onClick: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "${draft.title} ${if (draft.status == GenerationStatus.Queued) "대기중" else "생성중"}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = draft.progressLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCancel) {
                    Icon(painter = painterResource(id = R.drawable.ic_cancel), contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "취소")
                }
            }
        }
    }
}

@Composable
private fun AppCreateScreen(
    editingApp: SavedMiniApp?,
    onBack: () -> Unit,
    onGenerate: (AppGenerationRequest) -> Unit,
) {
    val editing = editingApp != null
    var prompt by remember(editingApp?.id) { mutableStateOf("") }
    var style by remember(editingApp?.id) { mutableStateOf(editingApp?.style ?: AppStyle.Calm) }
    var useLocalStorage by remember(editingApp?.id) { mutableStateOf(editingApp?.useLocalStorage ?: true) }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onBack) {
                    Text(text = "닫기")
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (editing) "앱 수정" else "새 앱 추가",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }

            if (editingApp != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(text = editingApp.title, fontWeight = FontWeight.Bold)
                        Text(
                            text = editingApp.prompt,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(156.dp),
                label = { Text(if (editing) "어떻게 수정할까요?" else "어떤 앱을 만들까요?") },
                placeholder = {
                    Text(
                        if (editing) "예: 버튼을 더 크게 만들고 점수 기록을 추가해줘"
                        else "예: 매일 물 마신 양을 기록하고 주간 목표를 보여주는 앱",
                    )
                },
                minLines = 5,
            )

            SettingGroup(title = "스타일") {
                AppStyle.entries.forEach {
                    SelectableRow(
                        title = it.label,
                        body = styleDescription(it),
                        selected = style == it,
                        onClick = { style = it },
                    )
                }
            }

            SwitchRow(
                title = "앱 안에 기록 저장",
                body = "필요한 경우 생성된 앱이 브라우저 저장소를 사용합니다.",
                checked = useLocalStorage,
                onCheckedChange = { useLocalStorage = it },
            )

            Button(
                onClick = {
                    onGenerate(
                        AppGenerationRequest(
                            prompt = if (editingApp == null) {
                                prompt.trim()
                            } else {
                                "기존 앱: ${editingApp.prompt}\n수정 요청: ${prompt.trim()}"
                            },
                            category = editingApp?.category ?: AppCategory.Utility,
                            style = style,
                            useLocalStorage = useLocalStorage,
                        ),
                    )
                },
                enabled = prompt.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onBackground),
            ) {
                Icon(painter = painterResource(id = R.drawable.ic_add), contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "생성")
            }
        }
    }
}

@Composable
private fun SettingGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun SwitchRow(
    title: String,
    body: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontWeight = FontWeight.Bold)
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun OnionBottomBar(
    selected: AppDestination,
    onOpenMarket: () -> Unit,
    onOpenHome: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BottomNavItem(
                icon = R.drawable.ic_market,
                label = "마켓",
                selected = selected == AppDestination.Market,
                onClick = onOpenMarket,
                modifier = Modifier.weight(1f),
            )
            BottomNavItem(
                icon = R.drawable.ic_home,
                label = "홈",
                selected = selected == AppDestination.Home,
                onClick = onOpenHome,
                modifier = Modifier.weight(1f),
            )
            BottomNavItem(
                icon = R.drawable.ic_settings,
                label = "설정",
                selected = selected == AppDestination.Settings,
                onClick = onOpenSettings,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BottomNavItem(
    icon: Int,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        onClick = onClick,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Icon(painter = painterResource(id = icon), contentDescription = label)
            Text(text = label, style = MaterialTheme.typography.labelMedium, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        }
    }
}

@Composable
private fun MarketScreen(
    onOpenHome: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            OnionBottomBar(
                selected = AppDestination.Market,
                onOpenMarket = {},
                onOpenHome = onOpenHome,
                onOpenSettings = onOpenSettings,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 22.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "마켓",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "나중에 공유 앱과 리믹스를 둘 공간입니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsScreen(
    strings: OnionStrings,
    selectedLanguage: AppLanguage,
    selectedThemeMode: AppThemeMode,
    toolState: AppCreationToolState,
    onLanguageSelected: (AppLanguage) -> Unit,
    onThemeSelected: (AppThemeMode) -> Unit,
    onSelectModel: (AppCreationToolModel) -> Unit,
    onStartDownload: () -> Unit,
    onBack: () -> Unit,
    onOpenHome: () -> Unit,
    onOpenMarket: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            OnionBottomBar(
                selected = AppDestination.Settings,
                onOpenMarket = onOpenMarket,
                onOpenHome = onOpenHome,
                onOpenSettings = {},
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onBack) {
                    Icon(painter = painterResource(id = R.drawable.ic_back), contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "닫기")
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "설정",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }

            ToolSetupCard(
                strings = strings,
                state = toolState,
                showSettings = true,
                onToggleSettings = {},
                onSelectModel = onSelectModel,
                onStartDownload = onStartDownload,
                showToggleButton = false,
            )

            SettingGroup(title = "언어") {
                AppLanguage.entries.forEach { language ->
                    SelectableRow(
                        title = language.nativeLabel,
                        body = language.label,
                        selected = selectedLanguage == language,
                        onClick = { onLanguageSelected(language) },
                    )
                }
            }

            SettingGroup(title = "화면 모드") {
                AppThemeMode.entries.forEach { mode ->
                    SelectableRow(
                        title = strings.themeLabel(mode),
                        body = strings.themeDescription(mode),
                        selected = selectedThemeMode == mode,
                        onClick = { onThemeSelected(mode) },
                    )
                }
            }

            OutlinedButton(
                onClick = {},
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(painter = painterResource(id = R.drawable.ic_settings), contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "버전 확인")
            }
        }
    }
}

@Composable
private fun AppPreviewScreen(
    draft: GeneratedAppDraft?,
    savedApp: SavedMiniApp?,
    onCancelDraft: () -> Unit,
    onBack: () -> Unit,
) {
    val title = draft?.title ?: savedApp?.title ?: "새 앱"
    val html = draft?.html ?: savedApp?.html.orEmpty()
    val status = draft?.status ?: GenerationStatus.Done
    val isSavedApp = savedApp != null && draft == null
    val hasPreviewHtml = html.isNotBlank()
    val titleText = when {
        draft != null && draft.title.isBlank() -> "앱 이름을 정하는 중..."
        status == GenerationStatus.Done -> "$title 앱 생성 완료"
        else -> "$title 앱 생성중..."
    }

    if (isSavedApp) {
        Box(modifier = Modifier.fillMaxSize()) {
            HtmlPreview(html = html, modifier = Modifier.fillMaxSize())
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(14.dp),
            ) {
                Text(text = "홈")
            }
        }
        return
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = draft?.progressLabel ?: "저장된 앱",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (status == GenerationStatus.Generating || status == GenerationStatus.Queued) {
                        OutlinedButton(onClick = onCancelDraft) {
                            Icon(painter = painterResource(id = R.drawable.ic_cancel), contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "취소")
                        }
                    }
                    OutlinedButton(onClick = onBack) {
                        Icon(painter = painterResource(id = R.drawable.ic_home), contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "홈")
                    }
                }
            }

            if (status == GenerationStatus.Generating || status == GenerationStatus.Queued) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (hasPreviewHtml) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Box(modifier = Modifier.padding(10.dp)) {
                        HtmlPreview(
                            html = html,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(18.dp)),
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = draft?.progressLabel ?: "AI가 계획 중이에요",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun HtmlPreview(
    html: String,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                webViewClient = WebViewClient()
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        },
    )
}

private fun categoryDescription(category: AppCategory): String {
    return when (category) {
        AppCategory.Utility -> "계산기, 변환기, 작은 도구"
        AppCategory.Tracker -> "습관, 건강, 일상 기록"
        AppCategory.Study -> "암기, 퀴즈, 학습 보조"
        AppCategory.Game -> "가벼운 인터랙티브 게임"
    }
}

private fun styleDescription(style: AppStyle): String {
    return when (style) {
        AppStyle.Calm -> "정돈된 기본 인터페이스"
        AppStyle.Playful -> "조금 더 밝고 경쾌한 화면"
        AppStyle.Focus -> "정보가 또렷한 집중형 화면"
    }
}

@Composable
private fun StatusDot(status: DownloadStatus) {
    val color = when (status) {
        DownloadStatus.NotDownloaded -> MaterialTheme.colorScheme.outline
        DownloadStatus.Downloading -> MaterialTheme.colorScheme.primary
        DownloadStatus.Ready -> MaterialTheme.colorScheme.tertiary
        DownloadStatus.Failed -> MaterialTheme.colorScheme.error
    }
    Box(
        modifier = Modifier
            .size(12.dp)
            .background(color = color, shape = CircleShape),
    )
}

private fun AppCreationToolState.downloadText(fallback: String): String {
    if (downloadedBytes <= 0L) return statusMessage ?: fallback
    val downloaded = downloadedBytes.toReadableSize()
    val total = totalBytes.takeIf { it > 0L }?.toReadableSize()
    return if (total == null) {
        "${statusMessage ?: fallback} · $downloaded"
    } else {
        "${statusMessage ?: fallback} · $downloaded / $total"
    }
}

private fun Long.toReadableSize(): String {
    val gb = this / 1_073_741_824.0
    if (gb >= 1.0) return String.format("%.1fGB", gb)
    val mb = this / 1_048_576.0
    return String.format("%.0fMB", mb)
}

private data class IntroPage(
    val title: String,
    val body: String,
)

private data class OnionStrings(
    val appTagline: String,
    val selectLanguageTitle: String,
    val selectLanguageBody: String,
    val selectThemeTitle: String,
    val selectThemeBody: String,
    val next: String,
    val chooseCreationTool: String,
    val creationTool: String,
    val downloadCreationTool: String,
    val skipForNow: String,
    val toolChoiceTitle: String,
    val toolChoiceBody: String,
    val backgroundDownloadNote: String,
    val homeHeadline: String,
    val advancedSettings: String,
    val close: String,
    val downloadingInBackground: String,
    val modelAutoSelected: String,
    val recommended: String,
    val preparing: String,
    val newApp: String,
    val newAppExamples: String,
    val describeApp: String,
    val prepareCreationTool: String,
    val creationToolRequired: String,
    val myApps: String,
    val emptyApps: String,
    val introPages: List<IntroPage>,
    val themeLabel: (AppThemeMode) -> String,
    val themeDescription: (AppThemeMode) -> String,
    val statusLabel: (AppCreationToolState) -> String,
    val modelDescription: (AppCreationToolModel) -> String,
    val recommendedTool: (AppCreationToolModel) -> String,
) {
    companion object {
        fun forLanguage(language: AppLanguage): OnionStrings {
            return when (language) {
                AppLanguage.Korean -> korean()
                AppLanguage.English -> english()
                AppLanguage.Japanese -> japanese()
                AppLanguage.Chinese -> chinese()
            }
        }

        private fun korean() = OnionStrings(
            appTagline = "내 기기에서 앱을 만드는 앱",
            selectLanguageTitle = "언어를 선택하세요",
            selectLanguageBody = "Onion에서 사용할 언어를 먼저 정합니다.",
            selectThemeTitle = "화면 모드를 선택하세요",
            selectThemeBody = "기본값은 휴대폰 설정을 따릅니다.",
            next = "다음",
            chooseCreationTool = "앱 생성 도구 선택",
            creationTool = "앱 생성 도구",
            downloadCreationTool = "앱 생성 도구 받기",
            skipForNow = "일단 건너뛰기",
            toolChoiceTitle = "앱 생성 도구를 받을까요?",
            toolChoiceBody = "Onion은 도구 없이도 둘러볼 수 있습니다. 새 앱을 만들 때는 앱 생성 도구가 필요합니다.",
            backgroundDownloadNote = "다운로드는 백그라운드에서도 계속됩니다.",
            homeHeadline = "만들고 싶은 앱을 말하면 Onion이 바로 실행 가능한 작은 앱으로 빚어줍니다.",
            advancedSettings = "상세 설정",
            close = "닫기",
            downloadingInBackground = "백그라운드에서 준비 중입니다. 다른 화면을 둘러봐도 괜찮아요.",
            modelAutoSelected = "기기 사양에 맞춰 자동으로 선택했습니다. 원하는 경우 직접 바꿀 수 있습니다.",
            recommended = "추천",
            preparing = "준비 중",
            newApp = "새 앱 만들기",
            newAppExamples = "예: 여행 경비 계산기, 매일 쓰는 체크리스트, 공부용 플래시카드",
            describeApp = "앱 설명하기",
            prepareCreationTool = "앱 생성 도구 준비하기",
            creationToolRequired = "Onion은 둘러볼 수 있지만, 앱을 만들려면 앱 생성 도구가 필요합니다.",
            myApps = "내 앱 내역",
            emptyApps = "아직 저장된 앱이 없습니다. 첫 앱을 만들면 여기에 모입니다.",
            introPages = listOf(
                IntroPage("말하면 앱이 됩니다", "원하는 앱을 자연스럽게 설명하면 Onion이 HTML 앱으로 만들어 바로 실행합니다."),
                IntroPage("내 기기에서 먼저", "앱 생성 도구는 가능한 한 로컬에서 동작하도록 준비됩니다. 개인 아이디어를 편하게 실험하세요."),
                IntroPage("작은 앱을 모아두세요", "만든 앱은 홈의 내 앱 내역에 쌓이고, 나중에는 공유와 리믹스로 확장됩니다."),
            ),
            themeLabel = {
                when (it) {
                    AppThemeMode.System -> "휴대폰 설정 따르기"
                    AppThemeMode.Light -> "라이트 모드"
                    AppThemeMode.Dark -> "다크 모드"
                }
            },
            themeDescription = {
                when (it) {
                    AppThemeMode.System -> "시스템 다크 모드 설정을 그대로 사용합니다."
                    AppThemeMode.Light -> "항상 밝은 화면을 사용합니다."
                    AppThemeMode.Dark -> "항상 어두운 화면을 사용합니다."
                }
            },
            statusLabel = {
                when (it.downloadStatus) {
                    DownloadStatus.NotDownloaded -> "필요할 때 받을 수 있습니다"
                    DownloadStatus.Downloading -> "${(it.progress * 100).toInt()}% 준비됨"
                    DownloadStatus.Ready -> "앱 만들 준비 완료"
                    DownloadStatus.Failed -> "준비 실패. 다시 시도해주세요"
                }
            },
            modelDescription = {
                when (it) {
                    AppCreationToolModel.Gemma4TwoB -> "가볍고 빠른 기본 앱 생성 도구"
                    AppCreationToolModel.Gemma4FourB -> "여유 있는 기기에서 더 풍부한 결과를 위한 앱 생성 도구"
                }
            },
            recommendedTool = { "${it.label}를 추천합니다" },
        )

        private fun english() = korean().copy(
            appTagline = "Create apps on your device",
            selectLanguageTitle = "Choose your language",
            selectLanguageBody = "Pick the language Onion should use.",
            selectThemeTitle = "Choose appearance",
            selectThemeBody = "By default, Onion follows your phone setting.",
            next = "Next",
            chooseCreationTool = "Choose app creation tool",
            creationTool = "App creation tool",
            downloadCreationTool = "Download app creation tool",
            skipForNow = "Skip for now",
            toolChoiceTitle = "Download the app creation tool?",
            toolChoiceBody = "You can browse Onion without it. It is required when you create a new app.",
            backgroundDownloadNote = "The download continues in the background.",
            homeHeadline = "Describe the app you want. Onion turns it into a small app you can run.",
            advancedSettings = "Advanced",
            close = "Close",
            downloadingInBackground = "Preparing in the background. You can keep using Onion.",
            modelAutoSelected = "Onion selected one for your device. You can change it manually.",
            recommended = "Recommended",
            preparing = "Preparing",
            newApp = "New app",
            newAppExamples = "Try: travel budget, daily checklist, study flashcards",
            describeApp = "Describe app",
            prepareCreationTool = "Prepare app creation tool",
            creationToolRequired = "You can browse Onion, but app creation requires the app creation tool.",
            myApps = "My app history",
            emptyApps = "No saved apps yet. Your first app will appear here.",
            introPages = listOf(
                IntroPage("Say it, make it", "Describe what you want and Onion creates a runnable HTML app."),
                IntroPage("Local first", "The app creation tool is prepared to work on your device whenever possible."),
                IntroPage("Keep your small apps", "Saved apps collect on your home screen. Sharing and remixing can come later."),
            ),
            themeLabel = {
                when (it) {
                    AppThemeMode.System -> "Follow phone setting"
                    AppThemeMode.Light -> "Light mode"
                    AppThemeMode.Dark -> "Dark mode"
                }
            },
            themeDescription = {
                when (it) {
                    AppThemeMode.System -> "Use your system appearance setting."
                    AppThemeMode.Light -> "Always use a light interface."
                    AppThemeMode.Dark -> "Always use a dark interface."
                }
            },
            statusLabel = {
                when (it.downloadStatus) {
                    DownloadStatus.NotDownloaded -> "Available when you need it"
                    DownloadStatus.Downloading -> "${(it.progress * 100).toInt()}% ready"
                    DownloadStatus.Ready -> "Ready to create apps"
                    DownloadStatus.Failed -> "Setup failed. Try again"
                }
            },
            modelDescription = {
                when (it) {
                    AppCreationToolModel.Gemma4TwoB -> "Light and fast default app creation tool"
                    AppCreationToolModel.Gemma4FourB -> "Richer results for capable devices"
                }
            },
            recommendedTool = { "${it.label} is recommended" },
        )

        private fun japanese() = korean().copy(
            appTagline = "端末上でアプリを作るアプリ",
            selectLanguageTitle = "言語を選択",
            selectLanguageBody = "Onionで使う言語を選んでください。",
            selectThemeTitle = "表示モードを選択",
            selectThemeBody = "初期設定では端末の設定に従います。",
            next = "次へ",
            chooseCreationTool = "アプリ生成ツールを選択",
            creationTool = "アプリ生成ツール",
            downloadCreationTool = "アプリ生成ツールを入手",
            skipForNow = "今はスキップ",
            toolChoiceTitle = "アプリ生成ツールを入手しますか？",
            toolChoiceBody = "ツールなしでもOnionを見られます。新しいアプリを作る時には必要です。",
            backgroundDownloadNote = "ダウンロードはバックグラウンドでも続きます。",
            homeHeadline = "作りたいアプリを説明すると、Onionがすぐ動く小さなアプリにします。",
            advancedSettings = "詳細設定",
            close = "閉じる",
            downloadingInBackground = "バックグラウンドで準備中です。他の画面を見ても大丈夫です。",
            modelAutoSelected = "端末に合わせて自動選択しました。必要なら変更できます。",
            recommended = "おすすめ",
            preparing = "準備中",
            newApp = "新しいアプリ",
            newAppExamples = "例: 旅行費用計算、毎日のチェックリスト、暗記カード",
            describeApp = "アプリを説明",
            prepareCreationTool = "アプリ生成ツールを準備",
            creationToolRequired = "Onionは見られますが、アプリ作成にはアプリ生成ツールが必要です。",
            myApps = "マイアプリ",
            emptyApps = "保存済みアプリはまだありません。最初のアプリがここに表示されます。",
            introPages = listOf(
                IntroPage("話せばアプリに", "欲しいものを説明すると、Onionが実行できるHTMLアプリを作ります。"),
                IntroPage("まずは端末で", "アプリ生成ツールは、できるだけ端末上で動くように準備されます。"),
                IntroPage("小さなアプリを保存", "作ったアプリはホームに集まります。共有やリミックスは後で広げられます。"),
            ),
            themeLabel = {
                when (it) {
                    AppThemeMode.System -> "端末設定に従う"
                    AppThemeMode.Light -> "ライトモード"
                    AppThemeMode.Dark -> "ダークモード"
                }
            },
            themeDescription = {
                when (it) {
                    AppThemeMode.System -> "システムの表示設定を使います。"
                    AppThemeMode.Light -> "常に明るい画面を使います。"
                    AppThemeMode.Dark -> "常に暗い画面を使います。"
                }
            },
            statusLabel = {
                when (it.downloadStatus) {
                    DownloadStatus.NotDownloaded -> "必要な時に入手できます"
                    DownloadStatus.Downloading -> "${(it.progress * 100).toInt()}% 準備完了"
                    DownloadStatus.Ready -> "アプリ作成の準備完了"
                    DownloadStatus.Failed -> "準備に失敗しました。再試行してください"
                }
            },
            modelDescription = {
                when (it) {
                    AppCreationToolModel.Gemma4TwoB -> "軽くて速い基本のアプリ生成ツール"
                    AppCreationToolModel.Gemma4FourB -> "余裕のある端末向けのより豊かなアプリ生成ツール"
                }
            },
            recommendedTool = { "${it.label} がおすすめです" },
        )

        private fun chinese() = korean().copy(
            appTagline = "在本机创建应用",
            selectLanguageTitle = "选择语言",
            selectLanguageBody = "先选择 Onion 使用的语言。",
            selectThemeTitle = "选择外观",
            selectThemeBody = "默认跟随手机系统设置。",
            next = "下一步",
            chooseCreationTool = "选择应用生成工具",
            creationTool = "应用生成工具",
            downloadCreationTool = "获取应用生成工具",
            skipForNow = "暂时跳过",
            toolChoiceTitle = "要获取应用生成工具吗？",
            toolChoiceBody = "没有工具也可以浏览 Onion。创建新应用时需要它。",
            backgroundDownloadNote = "下载会在后台继续。",
            homeHeadline = "描述你想要的应用，Onion 会把它变成可运行的小应用。",
            advancedSettings = "高级设置",
            close = "关闭",
            downloadingInBackground = "正在后台准备。你可以继续使用 Onion。",
            modelAutoSelected = "已根据设备自动选择，也可以手动更改。",
            recommended = "推荐",
            preparing = "准备中",
            newApp = "新建应用",
            newAppExamples = "例如：旅行预算、每日清单、学习卡片",
            describeApp = "描述应用",
            prepareCreationTool = "准备应用生成工具",
            creationToolRequired = "可以浏览 Onion，但创建应用需要应用生成工具。",
            myApps = "我的应用记录",
            emptyApps = "还没有保存的应用。第一个应用会显示在这里。",
            introPages = listOf(
                IntroPage("说出来，就能做成应用", "描述你想要的内容，Onion 会创建可运行的 HTML 应用。"),
                IntroPage("优先在本机运行", "应用生成工具会尽可能在你的设备上运行。"),
                IntroPage("保存你的小应用", "创建的应用会出现在首页。之后可以扩展分享和二次创作。"),
            ),
            themeLabel = {
                when (it) {
                    AppThemeMode.System -> "跟随手机设置"
                    AppThemeMode.Light -> "浅色模式"
                    AppThemeMode.Dark -> "深色模式"
                }
            },
            themeDescription = {
                when (it) {
                    AppThemeMode.System -> "使用系统外观设置。"
                    AppThemeMode.Light -> "始终使用浅色界面。"
                    AppThemeMode.Dark -> "始终使用深色界面。"
                }
            },
            statusLabel = {
                when (it.downloadStatus) {
                    DownloadStatus.NotDownloaded -> "需要时可以获取"
                    DownloadStatus.Downloading -> "已准备 ${(it.progress * 100).toInt()}%"
                    DownloadStatus.Ready -> "已准备好创建应用"
                    DownloadStatus.Failed -> "准备失败，请重试"
                }
            },
            modelDescription = {
                when (it) {
                    AppCreationToolModel.Gemma4TwoB -> "轻量快速的默认应用生成工具"
                    AppCreationToolModel.Gemma4FourB -> "适合高性能设备，生成结果更丰富"
                }
            },
            recommendedTool = { "推荐使用 ${it.label}" },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OnionHomeScreenPreview() {
    OnionTheme(themeMode = AppThemeMode.System) {
        OnionHomeScreen(
            strings = OnionStrings.forLanguage(AppLanguage.Korean),
            toolState = AppCreationToolState(
                selectedModel = AppCreationToolModel.Gemma4TwoB,
                recommendedModel = AppCreationToolModel.Gemma4TwoB,
                downloadStatus = DownloadStatus.NotDownloaded,
            ),
            generationState = AppGenerationState(),
            onAddApp = {},
            onOpenDraft = {},
            onOpenApp = {},
            onEditApp = {},
            onDeleteApp = {},
            onCancelDraft = {},
            onOpenMarket = {},
            onOpenSettings = {},
        )
    }
}
