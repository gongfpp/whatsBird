/*
 * Copyright 2026 gongfpp (https://github.com/gongfpp/whatsBird)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.whatsbird.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.whatsbird.R
import com.whatsbird.settings.AppSettings
import com.whatsbird.settings.SaveMode
import com.whatsbird.ui.theme.BirdColors
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: AppSettings,
    speciesCount: Int,
    modelVersion: String,
    onSaveMode: (SaveMode) -> Unit,
    onShowBoxes: (Boolean) -> Unit,
    onPreferGpu: (Boolean) -> Unit,
    onConfidence: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.set_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.set_close)) }
            }

            SectionLabel(stringResource(R.string.set_save_mode))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.saveMode == SaveMode.ORIGINAL,
                    onClick = { onSaveMode(SaveMode.ORIGINAL) },
                    label = { Text(stringResource(R.string.set_save_original)) },
                )
                FilterChip(
                    selected = settings.saveMode == SaveMode.LABELED,
                    onClick = { onSaveMode(SaveMode.LABELED) },
                    label = { Text(stringResource(R.string.set_save_labeled)) },
                )
                FilterChip(
                    selected = settings.saveMode == SaveMode.BOTH,
                    onClick = { onSaveMode(SaveMode.BOTH) },
                    label = { Text(stringResource(R.string.set_save_both)) },
                )
            }

            HorizontalDivider()

            ToggleRow(
                label = stringResource(R.string.set_show_boxes),
                checked = settings.showBoxes,
                onCheckedChange = onShowBoxes,
            )

            HorizontalDivider()

            SectionLabel(stringResource(R.string.set_advanced))

            ToggleRow(
                label = stringResource(R.string.set_use_gpu),
                checked = settings.preferGpu,
                onCheckedChange = onPreferGpu,
            )

            SectionLabel(stringResource(R.string.set_confidence))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = settings.confidenceThreshold >= 0.59f && settings.confidenceThreshold <= 0.61f,
                    onClick = { onConfidence(0.60f) },
                    label = { Text(stringResource(R.string.set_preset_conservative)) },
                )
                FilterChip(
                    selected = settings.confidenceThreshold >= 0.54f && settings.confidenceThreshold <= 0.56f,
                    onClick = { onConfidence(0.55f) },
                    label = { Text(stringResource(R.string.set_preset_balanced)) },
                )
                FilterChip(
                    selected = settings.confidenceThreshold >= 0.49f && settings.confidenceThreshold <= 0.51f,
                    onClick = { onConfidence(0.50f) },
                    label = { Text(stringResource(R.string.set_preset_more)) },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${(settings.confidenceThreshold * 100).roundToInt()}%",
                    style = MaterialTheme.typography.titleMedium,
                    color = BirdColors.Accent,
                )
            }
            Slider(
                value = settings.confidenceThreshold,
                onValueChange = onConfidence,
                valueRange = 0.25f..0.9f,
                steps = 12,
            )
            Text(
                text = stringResource(R.string.set_confidence_hint),
                style = MaterialTheme.typography.bodySmall,
                color = BirdColors.OnSurfaceMuted,
            )

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                SectionLabel(stringResource(R.string.set_species_count))
                Text(
                    text = "$speciesCount  ·  $modelVersion",
                    style = MaterialTheme.typography.bodySmall,
                    color = BirdColors.OnSurfaceMuted,
                )
            }

            SectionLabel(stringResource(R.string.set_about))
            Text(
                text = stringResource(R.string.set_about_body),
                style = MaterialTheme.typography.bodySmall,
                color = BirdColors.OnSurfaceMuted,
            )

            // Apache-2.0 requires the licence and the attribution notices to travel with the
            // binary, and the bundled model and third-party components are *not* covered by it —
            // say so rather than implying the whole app is open source.
            SectionLabel(stringResource(R.string.set_license))
            Text(
                text = stringResource(R.string.set_license_body),
                style = MaterialTheme.typography.bodySmall,
                color = BirdColors.OnSurfaceMuted,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
