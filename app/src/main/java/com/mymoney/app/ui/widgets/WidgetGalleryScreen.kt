package com.mymoney.app.ui.widgets

import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddToHomeScreen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.mymoney.app.ui.components.AppCard
import com.mymoney.app.ui.components.AppScaffold
import com.mymoney.app.ui.components.BaseViewModel
import com.mymoney.app.ui.components.Chip
import com.mymoney.app.ui.components.LabeledSwitchRow
import com.mymoney.app.ui.components.MessageBus
import com.mymoney.app.ui.components.SectionHeader
import com.mymoney.app.ui.components.TonalButton
import com.mymoney.app.ui.theme.FinanceTheme
import com.mymoney.app.widgets.WidgetKind
import com.mymoney.app.widgets.WidgetRepository
import com.mymoney.app.widgets.WidgetUpdater
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WidgetGalleryViewModel @Inject constructor(
    private val repo: WidgetRepository,
    @ApplicationContext private val context: Context,
    bus: MessageBus,
) : BaseViewModel(bus) {
    val prefs = repo.observePrefs().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val _previews = MutableStateFlow<Map<WidgetKind, RemoteViews?>>(emptyMap())
    val previews: StateFlow<Map<WidgetKind, RemoteViews?>> = _previews.asStateFlow()
    private val _placed = MutableStateFlow<Map<WidgetKind, Int>>(emptyMap())
    val placed: StateFlow<Map<WidgetKind, Int>> = _placed.asStateFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        WidgetKind.entries.forEach { k ->
            val rv = try { k.renderPreview(context, repo) } catch (e: Exception) { null }
            _previews.update { it + (k to rv) }
            _placed.update { it + (k to try { k.placedCount(context) } catch (e: Exception) { 0 }) }
        }
    }

    fun setPrivacy(on: Boolean) = launchSafe(if (on) "Widget privacy mode on" else "Widget privacy mode off") {
        repo.updatePrefs { it.copy(privacyMode = on) }
        WidgetUpdater.updateAll(context)
        refresh()
    }

    /** Returns false when the launcher can't pin widgets, so the UI shows manual instructions. */
    fun pin(k: WidgetKind): Boolean = k.requestPin(context)
}

@Composable
fun WidgetGalleryScreen(nav: NavController, vm: WidgetGalleryViewModel = hiltViewModel()) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val previews by vm.previews.collectAsStateWithLifecycle()
    val placed by vm.placed.collectAsStateWithLifecycle()
    var howTo by remember { mutableStateOf(false) }
    AppScaffold(title = "Home Screen Widgets", onBack = { nav.popBackStack() }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                AppCard {
                    LabeledSwitchRow("Widget privacy mode", "Mask every amount on your home screen widgets", prefs?.privacyMode == true, vm::setPrivacy)
                    Text("The Total Balance widget also has its own eye button to hide the balance.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                AppCard {
                    Text("How to add a widget", style = MaterialTheme.typography.titleSmall)
                    Text("Tap \"Add to home screen\" below, or long-press an empty spot on your home screen → Widgets → My Money, then drag a widget onto the screen. Widgets can be resized; layouts adapt to the size.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { SectionHeader("Available widgets (live preview)") }
            items(WidgetKind.entries, key = { it.name }) { k ->
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(k.title, style = MaterialTheme.typography.titleSmall)
                            Text(k.sizesLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val n = placed[k] ?: 0
                        if (n > 0) Chip("On home screen${if (n > 1) " ×$n" else ""}", FinanceTheme.colors.income)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(k.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth().height(k.previewSize.height), contentAlignment = Alignment.Center) {
                        val rv = previews[k]
                        if (rv == null) CircularProgressIndicator(Modifier.padding(16.dp))
                        else RemoteViewsPreview(rv, Modifier.widthIn(max = k.previewSize.width + 40.dp).fillMaxWidth().height(k.previewSize.height))
                    }
                    Spacer(Modifier.height(10.dp))
                    TonalButton("Add to home screen", { if (!vm.pin(k)) howTo = true }, Modifier.fillMaxWidth(), icon = Icons.Filled.AddToHomeScreen)
                }
            }
        }
    }
    if (howTo) AlertDialog(
        onDismissRequest = { howTo = false },
        title = { Text("Add it from your home screen") },
        text = { Text("Your launcher doesn't support adding widgets from apps. Long-press an empty area of your home screen, choose Widgets, find My Money and drag the widget you want onto the screen.") },
        confirmButton = { TextButton(onClick = { howTo = false }) { Text("OK") } },
    )
}

/** Inflates the real widget RemoteViews inside Compose. */
@Composable
private fun RemoteViewsPreview(rv: RemoteViews, modifier: Modifier) {
    val context = LocalContext.current
    AndroidView(
        factory = { FrameLayout(it) },
        update = { frame ->
            frame.removeAllViews()
            val v = try { rv.apply(context, frame) } catch (e: Exception) { null }
            if (v != null) frame.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        },
        modifier = modifier,
    )
}
