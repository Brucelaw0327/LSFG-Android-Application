package com.lsfg.android.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavHostController
import com.lsfg.android.R
import com.lsfg.android.ui.components.IconBadge
import com.lsfg.android.ui.components.LsfgCard
import com.lsfg.android.ui.components.LsfgTopBar
import com.lsfg.android.ui.theme.LsfgPrimary

/**
 * One step of the tutorial. Add new entries to [tutorialSteps] below as you
 * drop screenshots into res/drawable-nodpi.
 */
private data class TutorialStep(
    @StringRes val title: Int,
    @StringRes val description: Int,
    @DrawableRes val image: Int?,
)

/**
 * Tutorial entries. Replace the placeholder list with your own steps:
 *
 *   TutorialStep(
 *       title = "Step 1 — Pick the DLL",
 *       description = "Long description shown under the screenshot.",
 *       image = R.drawable.tutorial_step_1,
 *   ),
 *
 * Drop the PNG/JPG files into LSFG-Android/app/src/main/res/drawable-nodpi/
 * (any density-agnostic folder works) and reference them via R.drawable.<name>.
 * Leave [image] = null to render a text-only step.
 */
private val tutorialSteps: List<TutorialStep> = listOf(
    // TODO: aggiungi qui i tuoi step. Esempio:
    // TutorialStep(
    //     title = "1. Seleziona la DLL",
    //     description = "Apri 'Select DLL' dalla home e scegli Lossless.dll dalla tua copia di Lossless Scaling.",
    //     image = R.drawable.tutorial_step_1,
    // ),

    TutorialStep(
        title = R.string.tut_a11y_1_title,
        description = R.string.tut_a11y_1_desc,
        image = R.drawable.tutorial_step_1,
    ),

    TutorialStep(
        title = R.string.tut_a11y_2_title,
        description = R.string.tut_a11y_2_desc,
        image = R.drawable.tutorial_step_2,
    ),

    TutorialStep(
        title = R.string.tut_a11y_3_title,
        description = R.string.tut_a11y_3_desc,
        image = R.drawable.tutorial_step_3,
    ),

    TutorialStep(
        title = R.string.tut_a11y_4_title,
        description = R.string.tut_a11y_4_desc,
        image = R.drawable.tutorial_step_4,
    ),

    TutorialStep(
        title = R.string.tut_dll_1_title,
        description = R.string.tut_dll_1_desc,
        image = R.drawable.tutorial_step_5,
    ),

    TutorialStep(
        title = R.string.tut_dll_2_title,
        description = R.string.tut_dll_2_desc,
        image = R.drawable.tutorial_step_6,
    ),

    TutorialStep(
        title = R.string.tut_dll_3_title,
        description = R.string.tut_dll_3_desc,
        image = R.drawable.tutorial_step_7,
    ),

    TutorialStep(
        title = R.string.tut_dll_4_title,
        description = R.string.tut_dll_4_desc,
        image = R.drawable.tutorial_step_8,
    ),

    TutorialStep(
        title = R.string.tut_target_1_title,
        description = R.string.tut_target_1_desc,
        image = R.drawable.tutorial_step_9,
    ),

    TutorialStep(
        title = R.string.tut_target_2_title,
        description = R.string.tut_target_2_desc,
        image = R.drawable.tutorial_step_10,
    ),

    TutorialStep(
        title = R.string.tut_overlay_1_title,
        description = R.string.tut_overlay_1_desc,
        image = R.drawable.tutorial_step_11,
    ),

    TutorialStep(
        title = R.string.tut_overlay_2_title,
        description = R.string.tut_overlay_2_desc,
        image = R.drawable.tutorial_step_12,
    ),

    TutorialStep(
        title = R.string.tut_overlay_3_title,
        description = R.string.tut_overlay_3_desc,
        image = R.drawable.tutorial_step_13,
    ),

    TutorialStep(
        title = R.string.tut_overlay_4_title,
        description = R.string.tut_overlay_4_desc,
        image = R.drawable.tutorial_step_14,
    ),

    TutorialStep(
        title = R.string.tut_overlay_5_title,
        description = R.string.tut_overlay_5_desc,
        image = R.drawable.tutorial_step_15,
    ),

    TutorialStep(
        title = R.string.tut_overlay_6_title,
        description = R.string.tut_overlay_6_desc,
        image = R.drawable.tutorial_step_16,
    ),

    TutorialStep(
        title = R.string.tut_fg_1_title,
        description = R.string.tut_fg_1_desc,
        image = R.drawable.tutorial_step_17,
    ),

    TutorialStep(
        title = R.string.tut_fg_2_title,
        description = R.string.tut_fg_2_desc,
        image = R.drawable.tutorial_step_18,
    ),

    TutorialStep(
        title = R.string.tut_launch_1_title,
        description = R.string.tut_launch_1_desc,
        image = R.drawable.tutorial_step_19,
    ),

    TutorialStep(
        title = R.string.tut_launch_2_title,
        description = R.string.tut_launch_2_desc,
        image = R.drawable.tutorial_step_20,
    ),

    TutorialStep(
        title = R.string.tut_launch_3_title,
        description = R.string.tut_launch_3_desc,
        image = R.drawable.tutorial_step_21,
    ),

    TutorialStep(
        title = R.string.tut_menu_1_title,
        description = R.string.tut_menu_1_desc,
        image = R.drawable.tutorial_step_22,
    ),

    TutorialStep(
        title = R.string.tut_menu_2_title,
        description = R.string.tut_menu_2_desc,
        image = R.drawable.tutorial_step_23,
    ),

    TutorialStep(
        title = R.string.tut_menu_3_title,
        description = R.string.tut_menu_3_desc,
        image = R.drawable.tutorial_step_24,
    ),
    TutorialStep(
        title = R.string.tut_menu_4_title,
        description = 0,
        image = R.drawable.tutorial_step_25,
    ),
    TutorialStep(
        title = R.string.tut_menu_4_title,
        description = 0,
        image = R.drawable.tutorial_step_26,
    ),
    TutorialStep(
        title = R.string.tut_menu_4_title,
        description = 0,
        image = R.drawable.tutorial_step_27,
    ),
)

@Composable
fun TutorialScreen(nav: NavHostController) {
    var zoomedImage by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 20.dp),
    ) {
        LsfgTopBar(
            title = stringResource(R.string.nav_tutorial),
            onBack = { nav.popBackStack() },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 4.dp),
            ) {
                IconBadge(icon = Icons.Filled.School, tint = LsfgPrimary, size = 56.dp)
            }

            LsfgCard(accent = true) {
                Text(
                    text = stringResource(R.string.tutorial_subtitle).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = LsfgPrimary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.tutorial_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            if (tutorialSteps.isEmpty()) {
                LsfgCard {
                    Text(
                        text = stringResource(R.string.tutorial_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                tutorialSteps.forEachIndexed { index, step ->
                    TutorialStepCard(
                        index = index + 1,
                        step = step,
                        onImageClick = { res -> zoomedImage = res },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    zoomedImage?.let { res ->
        Dialog(
            onDismissRequest = { zoomedImage = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { zoomedImage = null },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(id = res),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentScale = ContentScale.Fit,
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(20.dp)
                        .size(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { zoomedImage = null },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close",
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun TutorialStepCard(
    index: Int,
    step: TutorialStep,
    onImageClick: (Int) -> Unit,
) {
    LsfgCard {
        Text(
            text = stringResource(R.string.tut_step_n, index.toString().padStart(2, '0')),
            style = MaterialTheme.typography.labelSmall,
            color = LsfgPrimary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(step.title),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (step.image != null) {
            Spacer(Modifier.height(12.dp))
            Image(
                painter = painterResource(id = step.image),
                contentDescription = stringResource(step.title),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onImageClick(step.image) },
            )
        }
        if (step.description != 0) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(step.description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
