package com.menouer.protocol.timeout

import com.menouer.rules_engine.model.TurnPhase

enum class TimeoutAction {
    AUTO_ROLL,
    AUTO_DECLINE_PURCHASE,
    AUTO_END_TURN,
    AUTO_DECLINE_TRADE
}

/**
 * What `normalActionTimeout` expiry auto-applies, per phase.
 * MultiplayerProtocol.md §13 names two explicit examples — "auto-decline an
 * unowned-property purchase... or auto-end-turn if no mandatory action
 * remains" — as illustrations of one underlying principle ("the host
 * auto-executes the least-committal legal default for the pending phase"),
 * not an exhaustive list. [defaultActionFor] extends that same principle to
 * every phase where a sensible, cost-free default exists:
 *
 * - `AWAITING_ROLL` / `AWAITING_JAIL_DECISION` -> [TimeoutAction.AUTO_ROLL].
 *   Rolling is the only way to leave either phase (there is no
 *   "decline to roll"), and rolling itself never costs the player
 *   anything or exercises a choice on their behalf beyond the mandatory
 *   minimum — the same "least committal" spirit as auto-declining a
 *   purchase.
 * - `AWAITING_PURCHASE_DECISION` -> [TimeoutAction.AUTO_DECLINE_PURCHASE] — §13's own named example.
 * - `AWAITING_OPTIONAL_ACTIONS` -> [TimeoutAction.AUTO_END_TURN] — §13's own named example.
 * - `IN_TRADE` -> [TimeoutAction.AUTO_DECLINE_TRADE]. §13's config list has
 *   no dedicated trade-response timeout, so a pending trade proposal falls
 *   under the generic `normalActionTimeout` instead; declining costs
 *   nothing, matching the same principle.
 * - `IN_AUCTION` -> `null`: has its own dedicated `auctionResponseTimeout`
 *   handled separately (see `HostSession.checkTimeouts`), not this one.
 * - `RESOLVING_LANDING` -> `null`: transient/host-internal only — a real
 *   client is never left waiting in this phase (`HostSession` always
 *   resolves it within the same commit chain that produced it).
 * - `GAME_OVER` -> `null`: nothing pending.
 */
object TimeoutPolicy {
    fun defaultActionFor(phase: TurnPhase): TimeoutAction? = when (phase) {
        TurnPhase.AWAITING_ROLL -> TimeoutAction.AUTO_ROLL
        TurnPhase.AWAITING_JAIL_DECISION -> TimeoutAction.AUTO_ROLL
        TurnPhase.AWAITING_PURCHASE_DECISION -> TimeoutAction.AUTO_DECLINE_PURCHASE
        TurnPhase.AWAITING_OPTIONAL_ACTIONS -> TimeoutAction.AUTO_END_TURN
        TurnPhase.IN_TRADE -> TimeoutAction.AUTO_DECLINE_TRADE
        TurnPhase.IN_AUCTION -> null
        TurnPhase.RESOLVING_LANDING -> null
        TurnPhase.GAME_OVER -> null
    }
}