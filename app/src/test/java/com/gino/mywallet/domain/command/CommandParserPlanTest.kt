package com.gino.mywallet.domain.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserPlanTest {
    private val parser = CommandParser()

    @Test
    fun parseTaskCreateWithWeekWhen() {
        val plan = parser.parse(
            """
            {
              "schema": "mywallet-cmd/v1",
              "mode": "preview",
              "commands": [
                {
                  "op": "task.create",
                  "title": "繳交作業",
                  "category": "學業",
                  "planned": { "week": 3, "weekday": 1 },
                  "planId": "114-1"
                }
              ]
            }
            """.trimIndent()
        )
        val cmd = plan.commands.single() as WalletCommand.TaskCreate
        assertEquals("繳交作業", cmd.title)
        assertEquals(3, cmd.planned?.week)
        assertEquals(1, cmd.planned?.weekday)
        assertEquals("114-1", cmd.planId)
    }

    @Test
    fun parseTaskCreateLegacyAppearDueMapsToPlanned() {
        val plan = parser.parse(
            """
            {
              "schema": "mywallet-cmd/v1",
              "mode": "preview",
              "commands": [
                {
                  "op": "task.create",
                  "title": "舊格式",
                  "category": "學業",
                  "appear": { "week": 2, "weekday": 3 },
                  "due": { "week": 2, "weekday": 5 }
                }
              ]
            }
            """.trimIndent()
        )
        val cmd = plan.commands.single() as WalletCommand.TaskCreate
        assertEquals(2, cmd.planned?.week)
        assertEquals(3, cmd.planned?.weekday)
    }

    @Test
    fun parseSemesterAndEventWeek() {
        val plan = parser.parse(
            """
            {
              "schema": "mywallet-cmd/v1",
              "mode": "apply",
              "commands": [
                { "op": "semester.set", "week1Monday": "2026-09-07", "weeks": 18 },
                { "op": "event.create", "title": "期中考", "week": 9, "weekday": 3, "time": "10:00" }
              ]
            }
            """.trimIndent()
        )
        val sem = plan.commands[0] as WalletCommand.SemesterSet
        val event = plan.commands[1] as WalletCommand.EventCreate
        assertEquals(18, sem.weeks)
        assertEquals(9, event.week)
        assertEquals(3, event.weekday)
        assertTrue(event.date == null)
    }

    @Test
    fun parseDesktopSyncOps() {
        val plan = parser.parse(
            """
            {
              "schema": "mywallet-cmd/v1",
              "mode": "apply",
              "note": "PC 暫代匯回",
              "commands": [
                { "op": "wallet.transfer", "from": "study", "to": "living", "yuan": 12.5, "note": "提現" },
                { "op": "task.complete", "id": 42, "title": "寫作業" },
                { "op": "task.fail", "title": "拖地" },
                { "op": "focus.settle", "minutes": 30, "cashYuan": 6, "grantTickets": true, "intent": "微積分" },
                { "op": "ticket.add", "kind": "SLOT", "n": 2 },
                { "op": "note", "text": "玩了拉霸但未記帳" }
              ]
            }
            """.trimIndent()
        )
        assertEquals(6, plan.commands.size)
        val transfer = plan.commands[0] as WalletCommand.WalletTransfer
        assertEquals("study", transfer.from)
        assertEquals(12.5, transfer.yuan, 0.0)
        val complete = plan.commands[1] as WalletCommand.TaskComplete
        assertEquals(42L, complete.id)
        assertEquals("寫作業", complete.title)
        val fail = plan.commands[2] as WalletCommand.TaskFail
        assertEquals("拖地", fail.title)
        val focus = plan.commands[3] as WalletCommand.FocusSettle
        assertEquals(30, focus.minutes)
        assertEquals(6, focus.cashYuan)
        val ticket = plan.commands[4] as WalletCommand.TicketAdd
        assertEquals("SLOT", ticket.kind)
        assertEquals(2, ticket.n)
        val note = plan.commands[5] as WalletCommand.Note
        assertEquals("玩了拉霸但未記帳", note.text)
    }

    @Test
    fun parseExamAndLoanOps() {
        val plan = parser.parse(
            """
            {
              "schema": "mywallet-cmd/v1",
              "mode": "apply",
              "commands": [
                { "op": "exam.save_course", "subject": "微積分", "note": "蔡老師", "units": ["1-1", "1-2"] },
                { "op": "exam.complete", "id": 9, "subject": "微積分", "title": "1-1" },
                { "op": "loan.borrow", "lender": "invest", "borrower": "living", "yuan": 100, "dueThisMonth": true },
                { "op": "loan.repay", "id": 3, "yuan": 50 },
                { "op": "loan.extend", "id": 3 }
              ]
            }
            """.trimIndent()
        )
        val save = plan.commands[0] as WalletCommand.ExamSaveCourse
        assertEquals("微積分", save.subject)
        assertEquals(listOf("1-1", "1-2"), save.units)
        val complete = plan.commands[1] as WalletCommand.ExamComplete
        assertEquals(9L, complete.id)
        val borrow = plan.commands[2] as WalletCommand.LoanBorrow
        assertEquals("invest", borrow.lender)
        assertTrue(borrow.dueThisMonth)
        val repay = plan.commands[3] as WalletCommand.LoanRepay
        assertEquals(3L, repay.id)
        assertEquals(50.0, repay.yuan, 0.0)
        val extend = plan.commands[4] as WalletCommand.LoanExtend
        assertEquals(3L, extend.id)
    }

    @Test
    fun parseWalletRate() {
        val plan = parser.parse(
            """
            {
              "schema": "mywallet-cmd/v1",
              "mode": "apply",
              "commands": [
                { "op": "wallet.rate", "walletId": "bci", "percent": 5 },
                { "op": "wallet.rate", "walletId": "acg", "percent": 3 }
              ]
            }
            """.trimIndent()
        )
        val bci = plan.commands[0] as WalletCommand.WalletRate
        assertEquals("bci", bci.walletId)
        assertEquals(5.0, bci.percent, 0.0)
        val acg = plan.commands[1] as WalletCommand.WalletRate
        assertEquals(3.0, acg.percent, 0.0)
    }

    @Test
    fun parseEventUpdateDeleteAndCourseDelete() {
        val plan = parser.parse(
            """
            {
              "schema": "mywallet-cmd/v1",
              "mode": "preview",
              "commands": [
                { "op": "event.update", "id": 12, "title": "改名", "date": "2026-11-01", "time": null },
                { "op": "event.delete", "id": 12 },
                { "op": "course.delete", "id": 3 }
              ]
            }
            """.trimIndent()
        )
        val upd = plan.commands[0] as WalletCommand.EventUpdate
        assertEquals(12L, upd.id)
        assertEquals("改名", upd.title)
        assertTrue(upd.clearTime)
        assertEquals(12L, (plan.commands[1] as WalletCommand.EventDelete).id)
        assertEquals(3L, (plan.commands[2] as WalletCommand.CourseDelete).id)
    }

    @Test
    fun parseTaskUpdateIncomeAndRewardGrant() {
        val plan = parser.parse(
            """
            {
              "schema": "mywallet-cmd/v1",
              "mode": "apply",
              "commands": [
                {
                  "op": "task.update",
                  "id": 7,
                  "ticketLevel": 5,
                  "reward": { "yuan": 20, "to": "living" },
                  "planned": null
                },
                { "op": "income.record", "yuan": 1000, "note": "零用" },
                {
                  "op": "reward.grant",
                  "nonce": "abc-1",
                  "yuan": 50,
                  "to": "study",
                  "ticket": { "kind": "SLOT", "n": 2 }
                }
              ]
            }
            """.trimIndent()
        )
        val upd = plan.commands[0] as WalletCommand.TaskUpdate
        assertEquals(7L, upd.id)
        assertEquals(5, upd.ticketLevel)
        assertEquals(20L, upd.rewardYuan)
        assertTrue(upd.clearPlanned)
        val income = plan.commands[1] as WalletCommand.IncomeRecord
        assertEquals(1000.0, income.yuan, 0.0)
        assertTrue(income.deferCentral)
        val grant = plan.commands[2] as WalletCommand.RewardGrant
        assertEquals("abc-1", grant.nonce)
        assertEquals(50.0, grant.yuan!!, 0.0)
        assertEquals("study", grant.to)
        assertEquals("SLOT", grant.ticketKind)
        assertEquals(2, grant.ticketN)
    }

    @Test
    fun parseTaskUpdateByTitleContains() {
        val plan = parser.parse(
            """
            {
              "schema": "mywallet-cmd/v1",
              "mode": "apply",
              "commands": [
                {
                  "op": "task.update",
                  "titleContains": "AISO",
                  "note": "截止改 22:00",
                  "ticketLevel": 5
                }
              ]
            }
            """.trimIndent()
        )
        val upd = plan.commands[0] as WalletCommand.TaskUpdate
        assertEquals(null, upd.id)
        assertEquals("AISO", upd.titleContains)
        assertEquals("截止改 22:00", upd.note)
        assertEquals(5, upd.ticketLevel)
    }

    @Test
    fun parseTaskDeleteByIdOrTitle() {
        val byId = parser.parse(
            """
            {
              "schema": "mywallet-cmd/v1",
              "mode": "apply",
              "commands": [{ "op": "task.delete", "id": 42 }]
            }
            """.trimIndent()
        )
        val delId = byId.commands[0] as WalletCommand.TaskDelete
        assertEquals(42L, delId.id)
        assertEquals(null, delId.titleContains)

        val byTitle = parser.parse(
            """
            {
              "schema": "mywallet-cmd/v1",
              "mode": "apply",
              "commands": [{ "op": "task.delete", "titleContains": "拖地", "status": "OPEN" }]
            }
            """.trimIndent()
        )
        val delTitle = byTitle.commands[0] as WalletCommand.TaskDelete
        assertEquals(null, delTitle.id)
        assertEquals("拖地", delTitle.titleContains)
        assertEquals("OPEN", delTitle.status)
    }
}
