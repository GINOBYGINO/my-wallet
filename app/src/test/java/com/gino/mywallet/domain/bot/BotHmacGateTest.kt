package com.gino.mywallet.domain.bot

import com.gino.mywallet.domain.command.CommandEnvelope
import com.gino.mywallet.domain.command.CommandPlan
import com.gino.mywallet.domain.command.CommandSchema
import com.gino.mywallet.domain.command.WalletCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BotHmacGateTest {
    private val secret = "test-token-xyz"
    private val softPayload =
        """{"schema":"mywallet-cmd/v1","id":"cmd-1","basedOnRevision":3,"mode":"apply","commands":[{"op":"note","text":"hi"}]}"""
    private val hardPayload =
        """{"schema":"mywallet-cmd/v1","id":"cmd-2","basedOnRevision":3,"mode":"apply","commands":[{"op":"ticket.add","kind":"SLOT","n":1}]}"""

    @Test
    fun signAndVerify() {
        val sig = BotHmac.signHex(secret, softPayload)
        assertTrue(BotHmac.verify(secret, softPayload, sig))
        assertFalse(BotHmac.verify(secret, softPayload, "deadbeef"))
        assertFalse(BotHmac.verify("other", softPayload, sig))
    }

    @Test
    fun gateAcceptsValidEnvelope() {
        val sig = BotHmac.signHex(secret, softPayload)
        val envelope = CommandEnvelope(CommandSchema.ENVELOPE_ID, "cmd-1", sig, softPayload)
        val plan = softPlan(basedOnRevision = 3L)
        assertNull(
            BotSyncGate.validate(secret, envelope, plan, currentRevision = 3L, alreadyApplied = false)
        )
    }

    @Test
    fun softPlanIgnoresRevisionMismatch() {
        val sig = BotHmac.signHex(secret, softPayload)
        val envelope = CommandEnvelope(CommandSchema.ENVELOPE_ID, "cmd-1", sig, softPayload)
        val plan = softPlan(basedOnRevision = 3L)
        assertNull(
            BotSyncGate.validate(
                secret, envelope, plan, currentRevision = 4L, alreadyApplied = false
            )
        )
    }

    @Test
    fun softPlanAllowsMissingBasedOnRevision() {
        val payload =
            """{"schema":"mywallet-cmd/v1","id":"cmd-1","mode":"apply","commands":[{"op":"note","text":"hi"}]}"""
        val sig = BotHmac.signHex(secret, payload)
        val envelope = CommandEnvelope(CommandSchema.ENVELOPE_ID, "cmd-1", sig, payload)
        val plan = softPlan(basedOnRevision = null)
        assertNull(
            BotSyncGate.validate(secret, envelope, plan, currentRevision = 9L, alreadyApplied = false)
        )
    }

    @Test
    fun hardPlanRejectsRevisionMismatch() {
        val sig = BotHmac.signHex(secret, hardPayload)
        val envelope = CommandEnvelope(CommandSchema.ENVELOPE_ID, "cmd-2", sig, hardPayload)
        val plan = hardPlan(basedOnRevision = 3L)
        val reject = BotSyncGate.validate(
            secret, envelope, plan, currentRevision = 4L, alreadyApplied = false
        )
        assertTrue(reject is BotSyncReject.RevisionMismatch)
        assertEquals(4L, (reject as BotSyncReject.RevisionMismatch).expected)
    }

    @Test
    fun hardPlanRequiresBasedOnRevision() {
        val payload =
            """{"schema":"mywallet-cmd/v1","id":"cmd-2","mode":"apply","commands":[{"op":"ticket.add","kind":"SLOT","n":1}]}"""
        val sig = BotHmac.signHex(secret, payload)
        val envelope = CommandEnvelope(CommandSchema.ENVELOPE_ID, "cmd-2", sig, payload)
        val plan = hardPlan(basedOnRevision = null)
        val reject = BotSyncGate.validate(
            secret, envelope, plan, currentRevision = 3L, alreadyApplied = false
        )
        assertTrue(reject is BotSyncReject.RevisionMismatch)
    }

    @Test
    fun gateRejectsMixedSoftHard() {
        val payload =
            """{"schema":"mywallet-cmd/v1","id":"cmd-3","basedOnRevision":3,"mode":"apply","commands":[{"op":"note","text":"hi"},{"op":"ticket.add","kind":"SLOT","n":1}]}"""
        val sig = BotHmac.signHex(secret, payload)
        val envelope = CommandEnvelope(CommandSchema.ENVELOPE_ID, "cmd-3", sig, payload)
        val plan = CommandPlan(
            schema = CommandSchema.ID,
            mode = "apply",
            commands = listOf(
                WalletCommand.Note("hi"),
                WalletCommand.TicketAdd("SLOT", 1)
            ),
            id = "cmd-3",
            basedOnRevision = 3L
        )
        assertTrue(
            BotSyncGate.validate(secret, envelope, plan, 3L, alreadyApplied = false)
                is BotSyncReject.MixedSoftHard
        )
    }

    @Test
    fun gateRejectsAlreadyApplied() {
        val sig = BotHmac.signHex(secret, softPayload)
        val envelope = CommandEnvelope(CommandSchema.ENVELOPE_ID, "cmd-1", sig, softPayload)
        val plan = softPlan(basedOnRevision = 3L)
        assertTrue(
            BotSyncGate.validate(secret, envelope, plan, 3L, alreadyApplied = true)
                is BotSyncReject.AlreadyApplied
        )
    }

    @Test
    fun gateAcceptsBatchBaseWhenCurrentAdvanced() {
        val sig = BotHmac.signHex(secret, hardPayload)
        val envelope = CommandEnvelope(CommandSchema.ENVELOPE_ID, "cmd-2", sig, hardPayload)
        val plan = hardPlan(basedOnRevision = 3L)
        assertNull(
            BotSyncGate.validate(
                secret,
                envelope,
                plan,
                currentRevision = 5L,
                alreadyApplied = false,
                batchBaseRevision = 3L
            )
        )
    }

    @Test
    fun gateRejectsHardWhenNeitherCurrentNorBatchBase() {
        val sig = BotHmac.signHex(secret, hardPayload)
        val envelope = CommandEnvelope(CommandSchema.ENVELOPE_ID, "cmd-2", sig, hardPayload)
        val plan = hardPlan(basedOnRevision = 3L)
        val reject = BotSyncGate.validate(
            secret,
            envelope,
            plan,
            currentRevision = 5L,
            alreadyApplied = false,
            batchBaseRevision = 4L
        )
        assertTrue(reject is BotSyncReject.RevisionMismatch)
        assertEquals(5L, (reject as BotSyncReject.RevisionMismatch).expected)
    }

    private fun softPlan(basedOnRevision: Long?) = CommandPlan(
        schema = CommandSchema.ID,
        mode = "apply",
        commands = listOf(WalletCommand.Note("hi")),
        id = "cmd-1",
        basedOnRevision = basedOnRevision
    )

    private fun hardPlan(basedOnRevision: Long?) = CommandPlan(
        schema = CommandSchema.ID,
        mode = "apply",
        commands = listOf(WalletCommand.TicketAdd("SLOT", 1)),
        id = "cmd-2",
        basedOnRevision = basedOnRevision
    )
}
