package com.menouer.capitalalgiers.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * SRS.md §3: 2-6 LAN players in general, but the local hotseat prototype
 * (DevelopmentRoadmap.md M3) caps at 4 — a single-device pass-and-play game
 * with 5-6 players is a UX problem for a later milestone to solve, not
 * something to design around here.
 */
private const val MIN_PLAYERS = 2
private const val MAX_PLAYERS = 4

/**
 * Lets the local group entering names before starting a hotseat match.
 * Blank names are auto-filled with "Player N" on start rather than blocked,
 * since requiring every name to be typed is friction this throwaway-quality
 * M3 UI doesn't need to impose (GameRules.md/SRS.md have no naming rule).
 */
@Composable
fun SetupScreen(
    onStartGame: (List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    val names = remember { mutableStateListOf("", "") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Capital Algiers",
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = "Local hotseat prototype — pass the device between turns.",
            style = MaterialTheme.typography.bodyMedium
        )

        Text(
            text = "Players (${names.size})",
            style = MaterialTheme.typography.titleMedium
        )

        LazyColumn(
            modifier = Modifier.weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(names.size) { index ->
                OutlinedTextField(
                    value = names[index],
                    onValueChange = { names[index] = it },
                    label = { Text("Player ${index + 1} name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { names.add("") },
                enabled = names.size < MAX_PLAYERS
            ) {
                Text("Add player")
            }
            Button(
                onClick = { names.removeAt(names.lastIndex) },
                enabled = names.size > MIN_PLAYERS
            ) {
                Text("Remove player")
            }
        }

        Button(
            onClick = {
                val resolvedNames = names.mapIndexed { index, name ->
                    name.trim().ifEmpty { "Player ${index + 1}" }
                }
                onStartGame(resolvedNames)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Start game")
        }
    }
}