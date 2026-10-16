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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.ime.nlp.OfflinePatternEngine
import dev.patrickgold.florisboard.lib.compose.FlorisScreen

@Composable
fun OfflineAiScreen() = FlorisScreen {
    title = "Offline AI"
    previewFieldVisible = false

    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(0) }
    var patternsText by remember { mutableStateOf(OfflinePatternEngine.readPatterns(context).joinToString("\n")) }
    var prompt by remember { mutableStateOf("") }
    val messages = remember { mutableStateListOf<Pair<Boolean, String>>() }

    content {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { selectedTab = 0 }) { Text("Pattern file") }
                Button(onClick = { selectedTab = 1 }) { Text("AI Chat") }
            }

            if (selectedTab == 0) {
                Text("Har line ek Hinglish pattern hai. Save karte hi keyboard aur chat dono ise use karenge.")
                OutlinedTextField(
                    value = patternsText,
                    onValueChange = { patternsText = it },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    label = { Text("pattern") },
                    minLines = 8,
                )
                Button(
                    onClick = {
                        OfflinePatternEngine.writePatterns(context, patternsText.lines())
                        patternsText = OfflinePatternEngine.readPatterns(context).joinToString("\n")
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save patterns") }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(messages) { message ->
                        Text(if (message.first) "You: ${message.second}" else "Offline AI: ${message.second}")
                    }
                }
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Message") },
                    minLines = 2,
                )
                Button(
                    onClick = {
                        val input = prompt.trim()
                        if (input.isNotEmpty()) {
                            messages.add(true to input)
                            val reply = OfflinePatternEngine.chatReply(context, input)
                            messages.add(false to "${reply.text}\nNext-word confidence estimate: ${reply.confidencePercent}%")
                            prompt = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Send") }
            }
        }
    }
}
