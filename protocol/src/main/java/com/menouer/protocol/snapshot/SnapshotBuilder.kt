package com.menouer.protocol.snapshot

import com.menouer.protocol.host.JoinedPlayer
import com.menouer.protocol.validation.MatchStatus
import com.menouer.rules_engine.model.GameState
import com.menouer.rules_engine.model.PlayerId

/**
 * Everything [SnapshotBuilder] needs to build one [GameStateSnapshot].
 * Mirrors `ValidationContext`'s own pattern (Session 2): `HostSession`
 * assembles one of these from its internal bookkeeping and calls
 * [SnapshotBuilder.build], keeping the actual translation logic a plain,
 * easily-testable function rather than a method entangled with
 * `HostSession`'s mutable state.
 *
 * [lobbyPlayers] supplies [SnapshotPlayer.displayName]/`ready`, which
 * `GameState`'s own `PlayerState` doesn't carry (display name and
 * readiness are lobby/protocol concerns, not rules-engine ones) — see
 * `JoinedPlayer`'s own doc.
 */
data class SnapshotContext(
    val gameId: String,
    val matchStatus: MatchStatus,
    val matchConfigId: String,
    val matchCapacity: Int,
    val stateVersion: Long,
    val lobbyPlayers: List<JoinedPlayer>,
    val connectedPlayerIds: Set<PlayerId>,
    val gameState: GameState?
)

object SnapshotBuilder {

    fun build(context: SnapshotContext): GameStateSnapshot {
        val state = context.gameState

        return GameStateSnapshot(
            gameId = context.gameId,
            matchStatus = context.matchStatus,
            matchConfigId = context.matchConfigId,
            stateVersion = context.stateVersion,
            matchCapacity = context.matchCapacity,
            players = buildPlayers(context, state),
            assets = state?.assets?.values
                ?.map { SnapshotAsset(it.id, it.ownerId, it.mortgaged, it.houses, it.hasHotel) }
                ?.sortedBy { it.assetId }
                ?: emptyList(),
            activePlayerId = state?.activePlayerId,
            phase = state?.phase,
            bankHouses = state?.bankHouses,
            bankHotels = state?.bankHotels,
            chanceDeckRemaining = state?.chanceDeck?.size,
            chestDeckRemaining = state?.chestDeck?.size,
            pendingAuction = state?.pendingAuction?.let {
                SnapshotAuction(it.assetId, it.highestBid, it.highestBidderId, it.eligibleBidders, it.passedBidders)
            },
            pendingTrade = state?.pendingTrade?.proposal?.let {
                SnapshotTrade(
                    fromPlayerId = it.fromPlayerId,
                    toPlayerId = it.toPlayerId,
                    offeredCash = it.offeredCash,
                    offeredAssets = it.offeredAssets,
                    offeredGetOutOfJailCards = it.offeredGetOutOfJailCards,
                    requestedCash = it.requestedCash,
                    requestedAssets = it.requestedAssets,
                    requestedGetOutOfJailCards = it.requestedGetOutOfJailCards
                )
            }
        )
    }

    /**
     * Before the match starts, players come entirely from the lobby roster
     * (no `GameState` exists to describe them). After start, the
     * authoritative source for gameplay fields is `GameState.players`, with
     * `displayName` looked up from the original lobby roster (rules-engine
     * has no concept of a display name — see `JoinedPlayer`'s doc).
     */
    private fun buildPlayers(context: SnapshotContext, state: GameState?): List<SnapshotPlayer> {
        if (state == null) {
            return context.lobbyPlayers.map { lobbyPlayer ->
                SnapshotPlayer(
                    playerId = lobbyPlayer.id,
                    displayName = lobbyPlayer.displayName,
                    connected = lobbyPlayer.id in context.connectedPlayerIds,
                    ready = lobbyPlayer.ready,
                    balance = null,
                    position = null,
                    inJail = null,
                    jailTurnsUsed = null,
                    getOutOfJailCards = emptyList(),
                    bankrupt = null
                )
            }
        }

        return state.players.map { player ->
            val lobbyPlayer = context.lobbyPlayers.firstOrNull { it.id == player.id }
            SnapshotPlayer(
                playerId = player.id,
                displayName = lobbyPlayer?.displayName ?: player.id,
                connected = player.id in context.connectedPlayerIds,
                ready = lobbyPlayer?.ready ?: true,
                balance = player.balance,
                position = player.position,
                inJail = player.inJail,
                jailTurnsUsed = player.jailTurnsUsed,
                getOutOfJailCards = player.getOutOfJailCards,
                bankrupt = player.bankrupt
            )
        }
    }
}