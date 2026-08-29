package com.menouer.protocol.error

import com.menouer.protocol.message.ErrorCode
import com.menouer.rules_engine.model.EngineError
import org.junit.Assert.assertEquals
import org.junit.Test

class EngineErrorMapperTest {

    @Test
    fun `every EngineError maps to some ErrorCode without throwing`() {
        for (error in EngineError.entries) {
            EngineErrorMapper.toErrorCode(error) // must not throw for any value
        }
    }

    @Test
    fun `insufficient funds maps directly`() {
        assertEquals(ErrorCode.INSUFFICIENT_FUNDS, EngineErrorMapper.toErrorCode(EngineError.INSUFFICIENT_FUNDS))
    }

    @Test
    fun `invalid bid maps directly`() {
        assertEquals(ErrorCode.INVALID_BID, EngineErrorMapper.toErrorCode(EngineError.INVALID_BID))
    }

    @Test
    fun `wrong phase maps to invalid phase`() {
        assertEquals(ErrorCode.INVALID_PHASE, EngineErrorMapper.toErrorCode(EngineError.WRONG_PHASE))
    }

    @Test
    fun `not active player maps to unauthorized player`() {
        assertEquals(ErrorCode.UNAUTHORIZED_PLAYER, EngineErrorMapper.toErrorCode(EngineError.NOT_ACTIVE_PLAYER))
    }

    @Test
    fun `asset not owned by player maps to unauthorized player`() {
        assertEquals(
            ErrorCode.UNAUTHORIZED_PLAYER,
            EngineErrorMapper.toErrorCode(EngineError.ASSET_NOT_OWNED_BY_PLAYER)
        )
    }

    @Test
    fun `group not complete maps to invalid payload`() {
        assertEquals(ErrorCode.INVALID_PAYLOAD, EngineErrorMapper.toErrorCode(EngineError.GROUP_NOT_COMPLETE))
    }
}