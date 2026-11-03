package com.gino.mywallet.domain

import com.gino.mywallet.data.entity.WalletCodes
import com.gino.mywallet.data.entity.WalletEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PrizeRouterTest {
    @Test
    fun cashWeightAtOrBelowSoftCapIsOne() {
        assertEquals(1.0, PrizeRouter.cashWeight(0), 1e-9)
        assertEquals(1.0, PrizeRouter.cashWeight(1500_00L), 1e-9)
    }

    @Test
    fun cashWeightAboveSoftCapScalesDown() {
        assertEquals(1500.0 / 7151.0, PrizeRouter.cashWeight(7151_00L), 1e-9)
        assertEquals(0.15, PrizeRouter.cashWeight(10_000_00L), 1e-9)
    }

    @Test
    fun eligibleExcludesCentralCardInvest() {
        val wallets = listOf(
            WalletEntity(WalletCodes.CENTRAL, "中央", isCentral = true),
            WalletEntity(WalletCodes.CENTRAL_FUTURE, "未來中央"),
            WalletEntity(WalletCodes.LIVING, "現金", balanceCents = 100_00),
            WalletEntity(WalletCodes.CARD, "提款卡"),
            WalletEntity(WalletCodes.INVEST, "投資"),
            WalletEntity(WalletCodes.STUDY, "學業"),
            WalletEntity(WalletCodes.BCI, "專案")
        )
        val ids = PrizeRouter.eligibleWallets(wallets).map { it.id }.toSet()
        assertEquals(setOf(WalletCodes.LIVING, WalletCodes.STUDY, WalletCodes.BCI), ids)
    }

    @Test
    fun routedPrizeArrowLabels() {
        assertEquals(" → 學業", RoutedPrize("study", "學業", 800).arrowLabel())
        assertEquals(
            " → 現金（待轉移）",
            RoutedPrize("living", "現金", 800, deferred = true).arrowLabel()
        )
    }

    @Test
    fun pickTargetIsDeterministicWithSeed() {
        val wallets = listOf(
            WalletEntity(WalletCodes.LIVING, "現金", balanceCents = 100_00),
            WalletEntity(WalletCodes.STUDY, "學業"),
            WalletEntity(WalletCodes.SOCIAL, "社交")
        )
        val a = PrizeRouter.pickTarget(wallets, Random(42))
        val b = PrizeRouter.pickTarget(wallets, Random(42))
        assertEquals(a.id, b.id)
    }
}

class WalletCodesTest {
    @Test
    fun piggyAndWalletBucket() {
        assertTrue(WalletCodes.isWalletBucket(WalletCodes.LIVING))
        assertTrue(WalletCodes.isWalletBucket(WalletCodes.CARD))
        assertFalse(WalletCodes.isPiggyWallet(WalletCodes.LIVING))
        assertFalse(WalletCodes.isPiggyWallet(WalletCodes.CARD))
        assertFalse(WalletCodes.isPiggyWallet(WalletCodes.INVEST))
        assertTrue(WalletCodes.isPiggyWallet(WalletCodes.STUDY))
        // 待轉移語意仍不含中央；UI 撲滿合計另含中央
        assertFalse(WalletCodes.isPiggyWallet(WalletCodes.CENTRAL))
        assertFalse(WalletCodes.isPiggyWallet(WalletCodes.CENTRAL_FUTURE))
    }
}
