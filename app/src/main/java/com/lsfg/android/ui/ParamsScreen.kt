package com.lsfg.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.ViewSidebar
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.lsfg.android.R
import com.lsfg.android.prefs.CaptureSource
import com.lsfg.android.prefs.DrawerEdge
import com.lsfg.android.prefs.LsfgPreferences
import com.lsfg.android.prefs.OverlayMode
import com.lsfg.android.session.AutoOverlayController
import com.lsfg.android.session.NativeBridge
import com.lsfg.android.ui.components.IconBadge
import com.lsfg.android.ui.components.LsfgCard
import com.lsfg.android.ui.components.LsfgTopBar
import com.lsfg.android.ui.components.SectionHeader
import com.lsfg.android.ui.components.ToggleRow
import com.lsfg.android.ui.components.ValueSlider
import com.lsfg.android.ui.theme.LsfgPrimary

// ----------------------------------------------------------------------------------------
// Frame generation & pacing — consolidated screen
// ----------------------------------------------------------------------------------------

@Composable
fun ParamsFrameGenPacingScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val prefs = remember { LsfgPreferences(ctx) }
    val state by produceConfigState(prefs).collectAsState()
    // The FP16 toggle is only meaningful when (a) the GPU advertises shaderFloat16
    // and (b) the FP16 SPIR-V cache from Lossless.dll has been populated. The
    // native probe ANDs both. Cached for the screen's lifetime — recomputed on
    // re-entry, which covers the post-DLL-pick / post-extract case naturally.
    val fp16Available = remember {
        val cacheDir = java.io.File(ctx.filesDir, "spirv").absolutePath
        runCatching { NativeBridge.isFramegenFp16Supported(cacheDir) }.getOrDefault(false)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LsfgTopBar(
            title = stringResource(R.string.nav_framegen_pacing),
            onBack = { nav.popBackStack() },
        )

        // ---- Frame generation -------------------------------------------------------
        LsfgCard {
            SectionHeader(eyebrow = stringResource(R.string.section_frame_generation), title = null)
            Spacer(Modifier.height(4.dp))

            ToggleRow(
                icon = Icons.Filled.FlashOn,
                title = stringResource(R.string.params_fg_title),
                description = stringResource(R.string.params_fg_desc),
                checked = state.lsfgEnabled,
                onCheckedChange = {
                    prefs.setLsfgEnabled(it)
                    refreshConfigState(prefs)
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))

            ValueSlider(
                title = stringResource(R.string.param_multiplier),
                valueDisplay = "${state.multiplier}×",
                description = stringResource(R.string.param_multiplier_desc),
                value = state.multiplier.toFloat(),
                range = 2f..8f,
                steps = 5,
                leadingIcon = Icons.Filled.Timeline,
                onValueChange = {
                    prefs.setMultiplier(it.toInt().coerceIn(2, 8))
                    refreshConfigState(prefs)
                },
            )

            ValueSlider(
                title = stringResource(R.string.param_flow_scale),
                valueDisplay = "%.2f".format(state.flowScale),
                description = stringResource(R.string.param_flow_scale_desc),
                value = state.flowScale,
                range = 0.25f..1.0f,
                steps = 0,
                leadingIcon = Icons.AutoMirrored.Filled.ShowChart,
                onValueChange = {
                    prefs.setFlowScale(it)
                    refreshConfigState(prefs)
                },
            )

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ToggleRow(
                icon = Icons.Filled.Speed,
                title = stringResource(R.string.param_performance_mode),
                description = stringResource(R.string.param_performance_mode_desc),
                checked = state.performanceMode,
                onCheckedChange = {
                    prefs.setPerformance(it)
                    refreshConfigState(prefs)
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ToggleRow(
                icon = Icons.Filled.AutoFixHigh,
                title = stringResource(R.string.param_anti_artifacts),
                description = stringResource(R.string.param_anti_artifacts_desc),
                checked = state.antiArtifacts,
                onCheckedChange = {
                    prefs.setAntiArtifacts(it)
                    refreshConfigState(prefs)
                },
            )
            if (fp16Available) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ToggleRow(
                    icon = Icons.Filled.Memory,
                    title = stringResource(R.string.param_framegen_fp16),
                    description = stringResource(R.string.param_framegen_fp16_desc),
                    checked = state.framegenFp16,
                    onCheckedChange = {
                        prefs.setFramegenFp16(it)
                        refreshConfigState(prefs)
                    },
                )
            }
        }

        // ---- Pacing -----------------------------------------------------------------
        LsfgCard {
            SectionHeader(eyebrow = stringResource(R.string.section_pacing), title = null)
            Spacer(Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.params_pacing_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.fmt_current_preset, when (state.pacingPreset.name) {
                    "SMOOTH" -> stringResource(R.string.preset_smooth)
                    "BALANCED" -> stringResource(R.string.preset_balanced)
                    "LOW_LATENCY" -> stringResource(R.string.preset_low_latency)
                    else -> stringResource(R.string.preset_custom)
                }) + " · " + stringResource(
                    R.string.fmt_vsync,
                    stringResource(if (state.vsyncAlignmentEnabled) R.string.state_on else R.string.state_off),
                ) + " · " + stringResource(
                    R.string.fmt_fpscap,
                    if (state.targetFpsCap > 0) "${state.targetFpsCap}" else stringResource(R.string.state_none),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = LsfgPrimary,
            )
        }

        TailNote()
    }
}

// ----------------------------------------------------------------------------------------
// Overlay & Display — new screen consolidating overlay handle, HUD, capture mode
// ----------------------------------------------------------------------------------------

@Composable
fun OverlayDisplayScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val prefs = remember { LsfgPreferences(ctx) }
    val state by produceConfigState(prefs).collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LsfgTopBar(
            title = stringResource(R.string.nav_overlay_display),
            onBack = { nav.popBackStack() },
        )

        // ---- Overlay entry mode (icon button vs drawer) -----------------------------
        LsfgCard {
            SectionHeader(eyebrow = stringResource(R.string.section_overlay_mode), title = null)
            Spacer(Modifier.height(4.dp))
            OverlayModeSelector(
                selected = state.overlayMode,
                onSelected = {
                    prefs.setOverlayMode(it)
                    refreshConfigState(prefs)
                    // Live-update the Automatic Overlay launcher: if the dot is
                    // currently shown for a target app it is recreated with the
                    // new affordance, otherwise the change is picked up the next
                    // time it appears.
                    AutoOverlayController.onOverlayModeChanged(ctx)
                },
            )
        }

        // ---- Overlay handle ---------------------------------------------------------
        LsfgCard {
            SectionHeader(eyebrow = stringResource(R.string.section_overlay_handle), title = null)
            Spacer(Modifier.height(4.dp))
            DrawerEdgeSelector(
                selected = state.drawerEdge,
                onSelected = {
                    prefs.setDrawerEdge(it)
                    refreshConfigState(prefs)
                },
            )
        }

        // ---- HUD --------------------------------------------------------------------
        LsfgCard {
            SectionHeader(eyebrow = stringResource(R.string.section_hud), title = null)
            Spacer(Modifier.height(4.dp))
            ToggleRow(
                icon = Icons.Filled.FlashOn,
                title = stringResource(R.string.param_fps_counter),
                description = stringResource(R.string.param_fps_counter_desc),
                checked = state.fpsCounterEnabled,
                onCheckedChange = {
                    prefs.setFpsCounterEnabled(it)
                    refreshConfigState(prefs)
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ToggleRow(
                icon = Icons.AutoMirrored.Filled.ShowChart,
                title = stringResource(R.string.param_frame_graph),
                description = stringResource(R.string.param_frame_graph_desc),
                checked = state.frameGraphEnabled,
                onCheckedChange = {
                    prefs.setFrameGraphEnabled(it)
                    refreshConfigState(prefs)
                },
            )
        }

        // ---- Trusted overlay (accessibility) ---------------------------------------
        LsfgCard {
            SectionHeader(eyebrow = stringResource(R.string.section_trusted_overlay), title = null)
            Spacer(Modifier.height(4.dp))
            ToggleRow(
                icon = Icons.Filled.TouchApp,
                title = stringResource(R.string.param_trusted_overlay),
                description = stringResource(R.string.param_trusted_overlay_desc),
                checked = state.trustedOverlay,
                onCheckedChange = {
                    prefs.setTrustedOverlay(it)
                    refreshConfigState(prefs)
                },
            )
        }

        TailNote()
    }
}

@Composable
private fun TailNote() {
    Text(
        text = stringResource(R.string.params_changes_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
}

// ----------------------------------------------------------------------------------------
// Helpers — Advanced expander + sub-section header
// ----------------------------------------------------------------------------------------

// ----------------------------------------------------------------------------------------
// Selectors shared across screens
// ----------------------------------------------------------------------------------------


@Composable
private fun OverlayModeSelector(
    selected: OverlayMode,
    onSelected: (OverlayMode) -> Unit,
) {
    var pendingDrawerConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp, horizontal = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon = Icons.Filled.TouchApp, size = 36.dp)
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.param_overlay_mode),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.param_overlay_mode_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selected == OverlayMode.ICON_BUTTON,
                onClick = {
                    if (selected != OverlayMode.ICON_BUTTON) onSelected(OverlayMode.ICON_BUTTON)
                },
                label = { Text(stringResource(R.string.overlay_mode_icon_button)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.TouchApp,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = selected == OverlayMode.DRAWER,
                onClick = {
                    if (selected != OverlayMode.DRAWER) {
                        pendingDrawerConfirm = true
                    }
                },
                label = { Text(stringResource(R.string.overlay_mode_drawer)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ViewSidebar,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (pendingDrawerConfirm) {
        AlertDialog(
            onDismissRequest = { pendingDrawerConfirm = false },
            icon = {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text(stringResource(R.string.overlay_mode_drawer_warning_title)) },
            text = { Text(stringResource(R.string.overlay_mode_drawer_warning_body)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDrawerConfirm = false
                    onSelected(OverlayMode.DRAWER)
                }) { Text(stringResource(R.string.overlay_mode_drawer_warning_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDrawerConfirm = false }) {
                    Text(stringResource(R.string.overlay_mode_drawer_warning_cancel))
                }
            },
        )
    }
}

@Composable
private fun DrawerEdgeSelector(
    selected: DrawerEdge,
    onSelected: (DrawerEdge) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp, horizontal = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon = Icons.Filled.OpenInFull, size = 36.dp)
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.param_drawer_edge),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.param_drawer_edge_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        val options = listOf(
            DrawerEdge.LEFT to stringResource(R.string.drawer_edge_left),
            DrawerEdge.RIGHT to stringResource(R.string.drawer_edge_right),
            DrawerEdge.TOP to stringResource(R.string.drawer_edge_top),
            DrawerEdge.BOTTOM to stringResource(R.string.drawer_edge_bottom),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.take(2).forEach { (edge, label) ->
                FilterChip(
                    selected = selected == edge,
                    onClick = { onSelected(edge) },
                    label = { Text(label) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.drop(2).forEach { (edge, label) ->
                FilterChip(
                    selected = selected == edge,
                    onClick = { onSelected(edge) },
                    label = { Text(label) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
