package com.menouer.economy_data

/**
 * Thrown when economy-config.json (or any other economy config source) fails
 * to load, parse, or validate. Always carries a specific, human-readable
 * message naming what was wrong and where — per DevelopmentRoadmap.md's M2
 * exit criterion: "deleting/corrupting a row in the data file fails a
 * startup validation check with a clear error, rather than silently
 * misbehaving mid-game."
 */
class EconomyConfigException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)