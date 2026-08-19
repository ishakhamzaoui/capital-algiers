package com.menouer.rules_engine.dice

/** The result of rolling two six-sided dice. */
data class DiceRoll(val die1: Int, val die2: Int) {
    init {
        require(die1 in 1..6) { "die1 must be 1..6, was $die1" }
        require(die2 in 1..6) { "die2 must be 1..6, was $die2" }
    }

    val total: Int get() = die1 + die2
    val isDouble: Boolean get() = die1 == die2
}

/**
 * Supplies dice rolls to the engine. Kept as an injectable interface (rather
 * than the engine calling kotlin.random directly) specifically so tests can
 * be fully deterministic, per DevelopmentRoadmap.md M1's exit criteria.
 */
interface DiceSource {
    fun roll(): DiceRoll
}

/** Real randomness for production use, still seedable for reproducible bug reports. */
class SeededDiceSource(seed: Long = System.nanoTime()) : DiceSource {
    private val random = kotlin.random.Random(seed)
    override fun roll(): DiceRoll = DiceRoll(random.nextInt(1, 7), random.nextInt(1, 7))
}

/**
 * Plays back a fixed, pre-scripted sequence of rolls. This is what the M1 exit
 * criterion's "full simulated game from start to a bankruptcy-driven win with
 * scripted dice" is built on.
 */
class ScriptedDiceSource(private val script: List<DiceRoll>) : DiceSource {
    private var index = 0

    override fun roll(): DiceRoll {
        check(index < script.size) { "ScriptedDiceSource exhausted after $index rolls" }
        return script[index++]
    }

    companion object {
        /** Convenience for tests: ScriptedDiceSource.of(1 to 2, 3 to 3, ...) */
        fun of(vararg rolls: Pair<Int, Int>): ScriptedDiceSource =
            ScriptedDiceSource(rolls.map { DiceRoll(it.first, it.second) })
    }
}
