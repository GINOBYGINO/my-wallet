package com.gino.mywallet.domain

import com.gino.mywallet.data.entity.WalletCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CentralPostingTest {
    @Test
    fun deferFollowsTheCheckbox() {
        assertEquals(WalletCodes.CENTRAL_FUTURE, centralShareDestination(deferCentral = true))
        assertEquals(WalletCodes.CENTRAL, centralShareDestination(deferCentral = false))
    }

    @Test
    fun routesOnlyCentralShare() {
        val splits = linkedMapOf(
            WalletCodes.CENTRAL to 400L,
            WalletCodes.LIVING to 500L,
            WalletCodes.BCI to 100L
        )
        val routed = routeCentralShare(splits, WalletCodes.CENTRAL_FUTURE)
        assertEquals(400L, routed[WalletCodes.CENTRAL_FUTURE])
        assertFalse(routed.containsKey(WalletCodes.CENTRAL))
        assertEquals(500L, routed[WalletCodes.LIVING])
        assertEquals(100L, routed[WalletCodes.BCI])
        assertEquals(splits, routeCentralShare(splits, WalletCodes.CENTRAL))
    }
}
