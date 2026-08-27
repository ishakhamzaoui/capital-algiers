package com.menouer.economy_data

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Loads the authoritative economy configuration (BoardEconomy.md / Cards.md,
 * transcribed once into economy-config.json) and produces a [BoardConfig] —
 * the same shape rules-engine already consumed via a hand-transcribed sample
 * fixture in M1 (since removed; this loader is the only source now).
 *
 * This class handles loading and parsing only. Structural validation beyond
 * "does this parse into well-formed DTOs" (40 positions present, 22
 * properties in 8 groups, house/hotel supply totals, exactly 2
 * GetOutOfJailFree cards, etc.) is a separate concern — see the M2 session
 * that adds a dedicated validator on top of this loader's output.
 *
 * [EconomyConfigException] is thrown, never a raw kotlinx.serialization
 * exception, so callers (and eventually a startup crash screen) get a
 * message that names the actual problem instead of a stack trace.
 */
object EconomyConfigLoader {

    /** Default classpath location of the single source-of-truth JSON asset. */
    const val DEFAULT_RESOURCE_PATH: String = "economy-config.json"

    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
    }

    /** Loads economy-config.json from its default bundled location. */
    fun loadDefault(): BoardConfig = loadFromResource(DEFAULT_RESOURCE_PATH)

    /**
     * Loads a JSON config from an arbitrary classpath resource path. Exposed
     * mainly for tests that want to exercise the loader against deliberately
     * corrupted fixtures without touching the real bundled asset.
     */
    fun loadFromResource(resourcePath: String): BoardConfig {
        val stream = EconomyConfigLoader::class.java.classLoader?.getResourceAsStream(resourcePath)
            ?: throw EconomyConfigException(
                "Economy config resource not found on classpath: '$resourcePath'. " +
                        "Expected it bundled at economy-data/src/main/resources/$resourcePath."
            )
        val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
        return loadFromJsonText(text, sourceLabel = resourcePath)
    }

    /**
     * Loads a JSON config from an in-memory string. [sourceLabel] is only
     * used to make error messages identify which source failed.
     */
    fun loadFromJsonText(text: String, sourceLabel: String = "<inline>"): BoardConfig {
        val dto = try {
            json.decodeFromString(EconomyConfigDto.serializer(), text)
        } catch (e: SerializationException) {
            throw EconomyConfigException(
                "Failed to parse economy config JSON from '$sourceLabel': ${e.message}", e
            )
        } catch (e: IllegalArgumentException) {
            // kotlinx.serialization surfaces some malformed-input cases (e.g.
            // strict-mode violations) as IllegalArgumentException rather than
            // SerializationException, depending on the failure point.
            throw EconomyConfigException(
                "Failed to parse economy config JSON from '$sourceLabel': ${e.message}", e
            )
        }
        return dto.toBoardConfig(sourceLabel)
    }
}