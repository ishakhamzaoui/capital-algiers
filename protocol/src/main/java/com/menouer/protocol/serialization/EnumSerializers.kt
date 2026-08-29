package com.menouer.protocol.serialization

import com.menouer.economy_data.Deck
import com.menouer.rules_engine.JailAction
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * rules-engine's [JailAction] and economy-data's [Deck] are plain Kotlin
 * enums with no `@Serializable` annotation, and this project's working
 * agreement is that `:protocol` never edits `:rules-engine`/`:economy-data`
 * production code (only reports a real bug there if one is found — this
 * isn't one, it's just a module boundary). These two objects let `:protocol`
 * serialize them anyway, encoding each as its enum name, without touching
 * either module. Referenced via `@Serializable(with = ...)` at each use site
 * in `ClientMessage`.
 */
object JailActionSerializer : KSerializer<JailAction> {
    override val descriptor = PrimitiveSerialDescriptor("JailAction", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: JailAction) {
        encoder.encodeString(value.name)
    }

    override fun deserialize(decoder: Decoder): JailAction =
        JailAction.valueOf(decoder.decodeString())
}

object DeckSerializer : KSerializer<Deck> {
    override val descriptor = PrimitiveSerialDescriptor("Deck", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Deck) {
        encoder.encodeString(value.name)
    }

    override fun deserialize(decoder: Decoder): Deck =
        Deck.valueOf(decoder.decodeString())
}