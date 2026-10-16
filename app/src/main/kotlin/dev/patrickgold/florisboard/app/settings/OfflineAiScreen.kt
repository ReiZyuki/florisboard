/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.ime.nlp.OfflinePatternEngine
import dev.patrickgold.florisboard.lib.compose.FlorisScreen

@Composable
fun OfflineAiScreen() = FlorisScreen {
    title = "Offline AI"
    previewFieldVisible = false

    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(1) }
    var patternsText by remember { mutableStateOf(OfflinePatternEngine.readPatterns(context).joinToString("\n")) }
    var prompt by remember { mutableStateOf("") }
    val messages = remember { mutableStateListOf<Pair<Boolean, String>>() }
    val colors = MaterialTheme.colorScheme

    content {
        Column(
            modifier = Modifier.fillMaxSize().background(colors.surface).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = colors.primaryContainer,
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Surface(shape = RoundedCornerShape(18.dp), color = colors.primary, modifier = Modifier.size(54.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("✦", color = colors.onPrimary, style = MaterialTheme.typography.headlineMedium)
                        }
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Your private AI space", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colors.onPrimaryContainer)
                        Text("Offline • Learns from your patterns", style = MaterialTheme.typography.bodySmall, color = colors.onPrimaryContainer)
                    }
                }
            }

            Surface(modifier = Modifier.fillMaxWidth(), shape = CircleShape, color = colors.surfaceVariant) {
                Row(modifier = Modifier.padding(5.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("✧  AI Chat", "▤  Pattern file").forEachIndexed { index, label ->
                        val tabIndex = if (index == 0) 1 else 0
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = CircleShape,
                            color = if (selectedTab == tabIndex) colors.primary else colors.surfaceVariant,
                            onClick = { selectedTab = tabIndex },
                        ) {
                            Text(
                                label,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (selectedTab == tabIndex) colors.onPrimary else colors.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }

            if (selectedTab == 0) {
                Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = colors.surfaceVariant) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Pattern library", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Har line ek sentence ya pattern hai. Chat aur keyboard inhi examples se seekhte hain.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                }
                OutlinedTextField(
                    value = patternsText,
                    onValueChange = { patternsText = it },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    label = { Text("Your patterns") },
                    placeholder = { Text("bilkul bro ab samjha") },
                    minLines = 8,
                    shape = RoundedCornerShape(18.dp),
                )
                Button(
                    onClick = {
                        OfflinePatternEngine.writePatterns(context, patternsText.lines())
                        patternsText = OfflinePatternEngine.readPatterns(context).joinToString("\n")
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) { Text("Save pattern library", fontWeight = FontWeight.SemiBold) }
            } else {
                Surface(modifier = Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(22.dp), color = colors.surfaceVariant) {
                    if (messages.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Surface(shape = CircleShape, color = colors.primaryContainer, modifier = Modifier.size(76.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("✦", style = MaterialTheme.typography.headlineLarge, color = colors.primary)
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            Text("Start a conversation", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Ask something. Your offline assistant will use the patterns saved on this device.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(16.dp))
                            Surface(shape = RoundedCornerShape(14.dp), color = colors.surface) {
                                Text("Try: Bro, mera idea yaad hai?", modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            itemsIndexed(messages) { _, message ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = if (message.first) Arrangement.End else Arrangement.Start,
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(0.88f),
                                        horizontalAlignment = if (message.first) Alignment.End else Alignment.Start,
                                        verticalArrangement = Arrangement.spacedBy(5.dp),
                                    ) {
                                        Text(if (message.first) "YOU" else "OFFLINE AI", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, fontWeight = FontWeight.Bold)
                                        Surface(
                                            shape = RoundedCornerShape(
                                                topStart = 18.dp,
                                                topEnd = 18.dp,
                                                bottomStart = if (message.first) 18.dp else 5.dp,
                                                bottomEnd = if (message.first) 5.dp else 18.dp,
                                            ),
                                            color = if (message.first) colors.primary else colors.surface,
                                        ) {
                                            Text(
                                                message.second,
                                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = if (message.first) colors.onPrimary else colors.onSurface,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message your offline AI…") },
                        minLines = 1,
                        maxLines = 4,
                        shape = RoundedCornerShape(22.dp),
                    )
                    Button(
                        onClick = {
                            val input = prompt.trim()
                            if (input.isNotEmpty()) {
                                messages.add(true to input)
                                val reply = OfflinePatternEngine.chatReply(context, input)
                                messages.add(false to "${reply.text}\n\nConfidence estimate: ${reply.confidencePercent}%")
                                prompt = ""
                            }
                        },
                        enabled = prompt.isNotBlank(),
                        modifier = Modifier.height(52.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) { Text("Send") }
                }
                Text("Runs locally • Replies depend on your saved patterns", modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
    }
}
