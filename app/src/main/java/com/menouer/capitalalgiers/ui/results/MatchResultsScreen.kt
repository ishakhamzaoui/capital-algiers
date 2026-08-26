package com.menouer.capitalalgiers.ui.results

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.menouer.capitalalgiers.game.FinalStanding

/**
 * SRS.md FR-013's "Match results" screen: who won, and the final line for
 * everyone else (GameRules.md §19-20). Bare-bones per DevelopmentRoadmap.md
 * M3 — the visual identity pass is M9's job, this just needs to exist and
 * be reachable so a played-out game doesn't dead-end at a caption buried in
 * the turn panel.
 */
@Composable
fun MatchResultsScreen(
    standings: List<FinalStanding>,
    onNewGame: () -> Unit,
    modifier: Modifier = Modifier
) {
    val winner = standings.firstOrNull { it.isWinner }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Game over", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = if (winner != null) "\uD83C\uDFC6 ${winner.name} wins!" else "No one is left standing.",
            style = MaterialTheme.typography.titleLarge
        )

        Text("Final standings", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))

        LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
            items(standings, key = { it.playerId }) { standing ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val prefix = if (standing.isWinner) "\uD83C\uDFC6 " else ""
                        Text("$prefix${standing.name}", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = if (standing.bankrupt) "Bankrupt" else "${standing.balance} \u062F\u062C",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }

        Button(onClick = onNewGame, modifier = Modifier.fillMaxWidth()) {
            Text("New game")
        }
    }
}