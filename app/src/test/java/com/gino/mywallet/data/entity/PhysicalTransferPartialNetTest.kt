package com.gino.mywallet.data.entity

import org.junit.Assert.assertEquals
import org.junit.Test

class PhysicalTransferPartialNetTest {
    @Test
    fun piggyNetSumsLivingInAndOut() {
        val items = listOf(
            PendingPhysicalTransferEntity(
                id = 1,
                amountCents = 300_00,
                fromWalletId = WalletCodes.CENTRAL,
                toWalletId = WalletCodes.LIVING
            ),
            PendingPhysicalTransferEntity(
                id = 2,
                amountCents = 100_00,
                fromWalletId = WalletCodes.LIVING,
                toWalletId = WalletCodes.STUDY
            )
        )
        assertEquals(200_00L, items.sumOf { it.livingNetCents() })
    }

    @Test
    fun focusPenaltyCountsAgainstPendingNet() {
        val items = listOf(
            PendingPhysicalTransferEntity(
                id = 1,
                amountCents = 80_00,
                fromWalletId = WalletCodes.CENTRAL,
                toWalletId = WalletCodes.LIVING,
                taskTitle = "專注結算 +80 元"
            ),
            PendingPhysicalTransferEntity(
                id = 2,
                amountCents = 50_00,
                fromWalletId = WalletCodes.LIVING,
                toWalletId = WalletCodes.CENTRAL,
                taskTitle = "專注結算 -50 元"
            )
        )
        assertEquals(80_00L, items[0].livingNetCents())
        assertEquals(-50_00L, items[1].livingNetCents())
        assertEquals(30_00L, items.sumOf { it.livingNetCents() })
    }
}
