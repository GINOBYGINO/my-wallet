package com.gino.mywallet.domain

import com.gino.mywallet.data.entity.TransactionEntity
import com.gino.mywallet.data.entity.WalletCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CashflowDigestTest {

    @Test
    fun incomeCreditsOnlyToWallet() {
        val tx = TransactionEntity(
            type = "INCOME_A",
            amountCents = 500_00,
            toWalletId = WalletCodes.LIVING
        )
        assertEquals(mapOf(WalletCodes.LIVING to 500_00L), transactionWalletDeltas(tx))
    }

    @Test
    fun donationDebitsOnlyFrom() {
        val tx = TransactionEntity(
            type = "DONATION",
            amountCents = 100_00,
            fromWalletId = WalletCodes.CENTRAL
        )
        assertEquals(mapOf(WalletCodes.CENTRAL to -100_00L), transactionWalletDeltas(tx))
    }

    @Test
    fun loanInterestDoesNotTouchWallets() {
        val tx = TransactionEntity(
            type = "LOAN_INTEREST",
            amountCents = 50_00,
            fromWalletId = WalletCodes.LIVING,
            toWalletId = WalletCodes.INVEST
        )
        assertTrue(transactionWalletDeltas(tx).isEmpty())
        assertTrue(isNonBalanceTransaction("LOAN_INTEREST"))
        assertTrue(isNonBalanceTransaction("CENTRAL_DEBT_REPAY"))
    }

    @Test
    fun balanceAdjustUsesDirection() {
        val up = TransactionEntity(
            type = "BALANCE_ADJUST",
            amountCents = 30_00,
            toWalletId = WalletCodes.LIVING
        )
        val down = TransactionEntity(
            type = "BALANCE_ADJUST",
            amountCents = 20_00,
            fromWalletId = WalletCodes.CARD
        )
        assertEquals(mapOf(WalletCodes.LIVING to 30_00L), transactionWalletDeltas(up))
        assertEquals(mapOf(WalletCodes.CARD to -20_00L), transactionWalletDeltas(down))
    }

    @Test
    fun digestBackCalculatesOpening() {
        val closing = mapOf(
            WalletCodes.LIVING to 1_000_00L,
            WalletCodes.CENTRAL to 400_00L
        )
        val txs = listOf(
            TransactionEntity(
                type = "INCOME_A",
                amountCents = 500_00,
                toWalletId = WalletCodes.LIVING
            ),
            TransactionEntity(
                type = "INCOME_A",
                amountCents = 400_00,
                toWalletId = WalletCodes.CENTRAL
            ),
            TransactionEntity(
                type = "LOAN_INTEREST",
                amountCents = 99_00,
                fromWalletId = WalletCodes.LIVING,
                toWalletId = WalletCodes.INVEST
            )
        )
        val digest = digestMonthCashflow("2026-10", closing, txs)
        val living = digest.wallets.first { it.walletId == WalletCodes.LIVING }
        assertEquals(500_00L, living.openingCents)
        assertEquals(1_000_00L, living.closingCents)
        assertEquals(500_00L, living.netCents)
        assertEquals(0L, digest.reconcilingGapCents)
        assertEquals(1, digest.nonBalanceNotes.size)
        assertTrue(digest.buckets.any { it.key == "income" && it.cents == 900_00L })
    }

    @Test
    fun bilateralTransferNetsBothSides() {
        val tx = TransactionEntity(
            type = "TRANSFER",
            amountCents = 100_00,
            fromWalletId = WalletCodes.BCI,
            toWalletId = WalletCodes.STUDY
        )
        assertEquals(
            mapOf(WalletCodes.BCI to -100_00L, WalletCodes.STUDY to 100_00L),
            transactionWalletDeltas(tx)
        )
    }
}
