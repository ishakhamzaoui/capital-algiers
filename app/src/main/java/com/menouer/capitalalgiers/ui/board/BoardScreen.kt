package com.menouer.capitalalgiers.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menouer.economy_data.BoardSpace
import com.menouer.economy_data.SpaceType
import com.menouer.rules_engine.model.PlayerId
import com.menouer.rules_engine.model.PlayerState

/** Distinguishing background tint per space type, purely for at-a-glance scanning (no group colors yet — that's M9's job). */
private fun colorFor(type: SpaceType): Color = when (type) {
    SpaceType.GO -> Color(0xFFBEE7BE)
    SpaceType.PROPERTY -> Color(0xFFF5F5F5)
    SpaceType.COMMUNITY_CHEST -> Color(0xFFCCE0FF)
    SpaceType.TAX -> Color(0xFFFFD8A8)
    SpaceType.STATION -> Color(0xFFE0D4F7)
    SpaceType.CHANCE -> Color(0xFFFFF3B0)
    SpaceType.JAIL -> Color(0xFFFFC9C9)
    SpaceType.UTILITY -> Color(0xFFD4F1F4)
    SpaceType.FREE_PARKING -> Color(0xFFE9ECEF)
    SpaceType.GO_TO_JAIL -> Color(0xFFFF8787)
}

private val tokenColors = listOf(
    Color(0xFFE03131), Color(0xFF1971C2), Color(0xFF2F9E44), Color(0xFFF08C00)
)

/**
 * Bare-bones, throwaway-quality board render per DevelopmentRoadmap.md M3
 * ("do not invest in visuals here, that's M6/M9"). Uses developerName
 * (Latin) as the on-screen label — RTL Arabic UI is explicitly out of scope
 * for this milestone (SRS §5/§6 belong to M6/M9).
 */
@Composable
fun BoardScreen(
    spaces: List<BoardSpace>,
    players: List<PlayerState>,
    playerNames: Map<PlayerId, String>,
    activePlayerId: PlayerId,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Capital Algiers", style = MaterialTheme.typography.headlineSmall)
            Button(onClick = onExit) { Text("Exit to setup") }
        }

        Scoreboard(
            players = players,
            playerNames = playerNames,
            activePlayerId = activePlayerId,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(spaces.sortedBy { it.index }, key = { it.index }) { space ->
                BoardSpaceCell(
                    space = space,
                    occupants = players.filter { it.position == space.index },
                    allPlayers = players
                )
            }
        }
    }
}

@Composable
private fun Scoreboard(
    players: List<PlayerState>,
    playerNames: Map<PlayerId, String>,
    activePlayerId: PlayerId,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp)) {
            players.forEachIndexed { index, player ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val label = playerNames[player.id] ?: player.id
                    val marker = if (player.id == activePlayerId) "\u25B6 " else "   "
                    Text(
                        text = "$marker$label" + if (player.bankrupt) " (bankrupt)" else "",
                        color = tokenColors[index % tokenColors.size],
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "${player.balance} \u062F\u062C",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun BoardSpaceCell(
    space: BoardSpace,
    occupants: List<PlayerState>,
    allPlayers: List<PlayerState>
) {
    Column(
        modifier = Modifier
            .background(colorFor(space.type))
            .border(1.dp, Color.Black.copy(alpha = 0.2f))
            .padding(4.dp)
            .size(width = 84.dp, height = 84.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "${space.index}",
            fontSize = 10.sp,
            color = Color.Gray
        )
        Text(
            text = space.developerName,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            maxLines = 3
        )
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            occupants.forEach { player ->
                val colorIndex = allPlayers.indexOf(player)
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(tokenColors[colorIndex % tokenColors.size], CircleShape)
                )
            }
        }
    }
}