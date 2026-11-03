package com.gino.mywallet.data.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhysicalTransferNotesTest {
    @Test
    fun repayAndBorrowTitlesRoundTrip() {
        assertEquals("還款 #12", PhysicalTransferNotes.repay(12))
        assertEquals(12L, PhysicalTransferNotes.parseRepayLoanId("還款 #12"))
        assertEquals("借款 #7", PhysicalTransferNotes.borrow(7))
        assertEquals(7L, PhysicalTransferNotes.parseBorrowLoanId("借款 #7"))
    }

    @Test
    fun unrelatedTitlesAreIgnored() {
        assertNull(PhysicalTransferNotes.parseRepayLoanId("獎懲轉帳"))
        assertNull(PhysicalTransferNotes.parseBorrowLoanId("還款 #12"))
        assertNull(PhysicalTransferNotes.parseRepayLoanId("借款 #12"))
    }
}
