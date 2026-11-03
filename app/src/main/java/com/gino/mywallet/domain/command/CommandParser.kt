package com.gino.mywallet.domain.command

import com.gino.mywallet.data.entity.TaskCategories
import com.gino.mywallet.data.entity.WalletCodes
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

class CommandParser(
    private val gson: Gson = Gson()
) {
    fun parse(raw: String): CommandPlan {
        val jsonText = extractJson(raw)
        val root = JsonParser.parseString(jsonText).asJsonObject
        val schema = root.get("schema")?.asString
            ?: error("缺少 schema，須為 ${CommandSchema.ID}")
        require(schema == CommandSchema.ID) { "不支援的 schema：$schema（預期 ${CommandSchema.ID}）" }

        val mode = root.get("mode")?.asString?.lowercase() ?: "apply"
        require(mode == "apply" || mode == "preview") { "mode 須為 apply 或 preview" }

        val note = root.get("note")?.takeIf { !it.isJsonNull }?.asString
        val id = root.get("id")?.takeIf { !it.isJsonNull }?.asString?.trim()?.ifBlank { null }
        val basedOn = root.get("basedOnRevision")?.takeIf { !it.isJsonNull }?.asLong
        val arr = root.getAsJsonArray("commands")
            ?: error("缺少 commands 陣列")
        require(arr.size() <= CommandSchema.MAX_COMMANDS) {
            "指令數不可超過 ${CommandSchema.MAX_COMMANDS}"
        }
        require(arr.size() > 0) { "commands 不可為空" }

        val commands = arr.mapIndexed { i, el ->
            try {
                parseCommand(el.asJsonObject)
            } catch (e: Exception) {
                error("commands[$i]：${e.message}")
            }
        }
        return CommandPlan(
            schema = schema,
            mode = mode,
            note = note,
            commands = commands,
            id = id,
            basedOnRevision = basedOn
        )
    }

    fun parseEnvelope(raw: String): CommandEnvelope {
        val root = JsonParser.parseString(raw.trim()).asJsonObject
        val schema = root.get("schema")?.asString
            ?: error("缺少 schema，須為 ${CommandSchema.ENVELOPE_ID}")
        require(schema == CommandSchema.ENVELOPE_ID) {
            "不支援的信封 schema：$schema"
        }
        val id = root.get("id")?.asString?.trim().orEmpty()
        require(id.isNotBlank()) { "信封缺少 id" }
        val sig = root.get("sig")?.asString?.trim().orEmpty()
        require(sig.isNotBlank()) { "信封缺少 sig" }
        val payloadEl = root.get("payload") ?: error("信封缺少 payload")
        val payload = when {
            payloadEl.isJsonPrimitive && payloadEl.asJsonPrimitive.isString ->
                payloadEl.asString
            else -> error("payload 須為字串（指令 JSON 原文）")
        }
        require(payload.isNotBlank()) { "payload 不可空白" }
        return CommandEnvelope(schema = schema, id = id, sig = sig, payload = payload)
    }

    /** 從貼上文字抽出 JSON（支援 ```mywallet-cmd fence） */
    fun extractJson(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) error("請貼上指令內容")

        val fence = Regex(
            """```(?:mywallet-cmd)?\s*\n?(.*?)\n?```""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val match = fence.find(trimmed)
        if (match != null) {
            return match.groupValues[1].trim()
        }
        // 直接 JSON 物件
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1)
        }
        error("找不到 JSON；請貼上 ```mywallet-cmd ... ``` 區塊或完整 JSON")
    }

    private fun parseCommand(obj: JsonObject): WalletCommand {
        val op = obj.get("op")?.asString ?: error("缺少 op")
        return when (op) {
            "task.create" -> parseTaskCreate(obj)
            "course.upsert" -> parseCourseUpsert(obj)
            "course.replace_all" -> {
                val courses = obj.getAsJsonArray("courses")
                    ?: error("course.replace_all 需要 courses 陣列")
                WalletCommand.CourseReplaceAll(
                    courses = courses.mapIndexed { i, el ->
                        try {
                            parseCourseUpsert(el.asJsonObject)
                        } catch (e: Exception) {
                            error("courses[$i]：${e.message}")
                        }
                    }
                )
            }
            "event.create" -> parseEventCreate(obj)
            "event.update" -> parseEventUpdate(obj)
            "event.delete" -> {
                val id = obj.get("id")?.asLong ?: error("缺少 id")
                WalletCommand.EventDelete(id = id)
            }
            "task.update" -> parseTaskUpdate(obj)
            "course.delete" -> {
                val id = obj.get("id")?.asLong ?: error("缺少 id")
                WalletCommand.CourseDelete(id = id)
            }
            "income.record" -> parseIncomeRecord(obj)
            "reward.grant" -> parseRewardGrant(obj)
            "task.delete" -> {
                val id = obj.get("id")?.takeIf { !it.isJsonNull }?.asLong
                val titleContains = obj.get("titleContains")?.takeIf { !it.isJsonNull }
                    ?.asString?.trim()?.ifBlank { null }
                require(id != null || !titleContains.isNullOrBlank()) {
                    "須提供 id 或 titleContains"
                }
                val status = obj.get("status")?.asString?.uppercase() ?: "OPEN"
                require(status == "OPEN" || status == "ALL") { "status 僅支援 OPEN 或 ALL" }
                WalletCommand.TaskDelete(
                    id = id,
                    titleContains = titleContains,
                    status = status
                )
            }
            "semester.set" -> parseSemesterSet(obj)
            "plan.upsert" -> parsePlanUpsert(obj)
            "plan.import" -> parsePlanImport(obj)
            "wallet.transfer" -> parseWalletTransfer(obj)
            "task.complete" -> parseTaskResolve(obj, complete = true)
            "task.fail" -> parseTaskResolve(obj, complete = false)
            "focus.settle" -> parseFocusSettle(obj)
            "ticket.add" -> parseTicketAdd(obj)
            "note" -> {
                val text = obj.get("text")?.asString?.trim().orEmpty()
                require(text.isNotBlank()) { "text 不可空白" }
                WalletCommand.Note(text = text)
            }
            "exam.save_course" -> parseExamSaveCourse(obj)
            "exam.complete" -> parseExamItemRef(obj) { id, subject, title ->
                WalletCommand.ExamComplete(id, subject, title)
            }
            "exam.enqueue" -> parseExamItemRef(obj) { id, subject, title ->
                WalletCommand.ExamEnqueue(id, subject, title)
            }
            "exam.dequeue" -> parseExamItemRef(obj) { id, subject, title ->
                WalletCommand.ExamDequeue(id, subject, title)
            }
            "exam.delete_item" -> parseExamItemRef(obj) { id, subject, title ->
                WalletCommand.ExamDeleteItem(id, subject, title)
            }
            "loan.borrow" -> parseLoanBorrow(obj)
            "loan.repay" -> {
                val id = obj.get("id")?.asLong ?: error("缺少 id")
                val yuan = obj.get("yuan")?.asDouble ?: error("缺少 yuan")
                require(yuan > 0) { "yuan 須為正數" }
                WalletCommand.LoanRepay(id = id, yuan = yuan)
            }
            "loan.extend" -> {
                val id = obj.get("id")?.asLong ?: error("缺少 id")
                WalletCommand.LoanExtend(id = id)
            }
            "wallet.rate" -> {
                val walletId = obj.get("walletId")?.asString?.trim()
                    ?: obj.get("id")?.asString?.trim()
                    ?: error("缺少 walletId")
                requireWalletId(walletId, "walletId")
                require(walletId != WalletCodes.CENTRAL) { "central 利率不可由此指令修改" }
                val percent = obj.get("percent")?.asDouble
                    ?: error("缺少 percent（例如 5 = 5%/月）")
                require(percent >= 0) { "percent 不可為負" }
                WalletCommand.WalletRate(walletId = walletId, percent = percent)
            }
            "wallet.confirm_physical" -> {
                val yuan = obj.get("yuan")?.takeIf { !it.isJsonNull }?.asDouble
                if (yuan != null) require(yuan >= 0) { "yuan 不可為負" }
                WalletCommand.WalletConfirmPhysical(yuan = yuan)
            }
            else -> error("未知 op：$op")
        }
    }

    private fun parseExamSaveCourse(obj: JsonObject): WalletCommand.ExamSaveCourse {
        val subject = obj.get("subject")?.asString?.trim().orEmpty()
        require(subject.isNotBlank()) { "subject 不可空白" }
        val units = mutableListOf<String>()
        obj.get("units")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { el ->
            val u = el.asString.trim()
            if (u.isNotEmpty()) units += u
        }
        return WalletCommand.ExamSaveCourse(
            subject = subject,
            note = obj.get("note")?.asString?.trim().orEmpty(),
            units = units
        )
    }

    private fun parseExamItemRef(
        obj: JsonObject,
        build: (Long?, String?, String?) -> WalletCommand
    ): WalletCommand {
        val id = obj.get("id")?.takeIf { !it.isJsonNull }?.asLong
        val subject = obj.get("subject")?.takeIf { !it.isJsonNull }?.asString?.trim()?.ifBlank { null }
        val title = obj.get("title")?.takeIf { !it.isJsonNull }?.asString?.trim()?.ifBlank { null }
        require(id != null || !title.isNullOrBlank()) { "須提供 id 或 title" }
        return build(id, subject, title)
    }

    private fun parseLoanBorrow(obj: JsonObject): WalletCommand.LoanBorrow {
        val lender = obj.get("lender")?.asString?.trim().orEmpty()
        val borrower = obj.get("borrower")?.asString?.trim().orEmpty()
        require(lender.isNotBlank() && borrower.isNotBlank()) { "lender／borrower 不可空白" }
        requireWalletId(lender, "lender")
        requireWalletId(borrower, "borrower")
        val yuan = obj.get("yuan")?.asDouble ?: error("缺少 yuan")
        require(yuan > 0) { "yuan 須為正數" }
        val due = obj.get("dueThisMonth")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
        return WalletCommand.LoanBorrow(
            lender = lender,
            borrower = borrower,
            yuan = yuan,
            dueThisMonth = due
        )
    }

    private fun parseWalletTransfer(obj: JsonObject): WalletCommand.WalletTransfer {
        val from = obj.get("from")?.asString?.trim().orEmpty()
        val to = obj.get("to")?.asString?.trim().orEmpty()
        require(from.isNotBlank() && to.isNotBlank()) { "from／to 不可空白" }
        require(from != to) { "from 與 to 不可相同" }
        val yuanEl = obj.get("yuan") ?: error("缺少 yuan")
        val yuan = yuanEl.asDouble
        require(yuan > 0) { "yuan 須為正數" }
        requireWalletId(from, "from")
        requireWalletId(to, "to")
        require(from != WalletCodes.CENTRAL) { "from 不可為 central" }
        return WalletCommand.WalletTransfer(
            from = from,
            to = to,
            yuan = yuan,
            note = obj.get("note")?.asString?.trim().orEmpty()
        )
    }

    private fun parseTaskResolve(obj: JsonObject, complete: Boolean): WalletCommand {
        val id = obj.get("id")?.takeIf { !it.isJsonNull }?.asLong
        val title = obj.get("title")?.takeIf { !it.isJsonNull }?.asString?.trim()?.ifBlank { null }
        require(id != null || !title.isNullOrBlank()) { "須提供 id 或 title" }
        return if (complete) {
            WalletCommand.TaskComplete(id = id, title = title)
        } else {
            WalletCommand.TaskFail(id = id, title = title)
        }
    }

    private fun parseFocusSettle(obj: JsonObject): WalletCommand.FocusSettle {
        val minutes = obj.get("minutes")?.asInt ?: error("缺少 minutes")
        require(minutes in 0..240) { "minutes 須為 0–240" }
        val cash = obj.get("cashYuan")?.takeIf { !it.isJsonNull }?.asInt
        val grant = obj.get("grantTickets")?.takeIf { !it.isJsonNull }?.asBoolean ?: true
        return WalletCommand.FocusSettle(
            minutes = minutes,
            cashYuan = cash,
            grantTickets = grant,
            intent = obj.get("intent")?.asString?.trim().orEmpty().ifBlank { "PC暫代" },
            category = obj.get("category")?.asString?.trim().orEmpty().ifBlank { "其他" }
        )
    }

    private fun parseTicketAdd(obj: JsonObject): WalletCommand.TicketAdd {
        val kind = obj.get("kind")?.asString?.trim()?.uppercase().orEmpty()
        require(kind.isNotBlank()) { "缺少 kind" }
        val n = obj.get("n")?.asInt ?: 1
        require(n > 0) { "n 須 > 0" }
        return WalletCommand.TicketAdd(kind = kind, n = n)
    }

    private fun requireWalletId(id: String, field: String) {
        require(
            id in listOf(
                WalletCodes.LIVING, WalletCodes.CARD, WalletCodes.INVEST, WalletCodes.STUDY,
                WalletCodes.BCI, WalletCodes.VTUBER, WalletCodes.ACG, WalletCodes.SOCIAL,
                WalletCodes.CENTRAL, WalletCodes.CENTRAL_FUTURE
            )
        ) { "$field 錢包 id 無效：$id" }
    }

    private fun parseTaskCreate(obj: JsonObject): WalletCommand.TaskCreate {
        val title = obj.get("title")?.asString?.trim().orEmpty()
        require(title.isNotBlank()) { "title 不可空白" }

        val category = obj.get("category")?.asString?.trim() ?: "其他"
        require(category in TaskCategories.PRESETS) {
            "category 須為 ${TaskCategories.PRESETS.joinToString("/")}"
        }

        val ticketLevel = obj.get("ticketLevel")?.asInt ?: 2
        require(ticketLevel in 1..9) { "ticketLevel 須為 1–9" }

        val repeatable = obj.get("repeatable")?.asBoolean ?: false
        var repeatFreq = "daily"
        var repeatN: Int? = null
        if (repeatable) {
            val repeat = obj.get("repeat")?.takeIf { it.isJsonObject }?.asJsonObject
            if (repeat != null) {
                repeatFreq = repeat.get("freq")?.asString ?: "daily"
                require(repeatFreq in listOf("daily", "weekly", "every_n")) {
                    "repeat.freq 須為 daily/weekly/every_n"
                }
                if (repeatFreq == "every_n") {
                    repeatN = repeat.get("n")?.asInt
                    require(repeatN != null && repeatN!! >= 1) { "every_n 須提供 n≥1" }
                }
            }
        }

        var rewardYuan: Long? = null
        var rewardTo = WalletCodes.LIVING
        obj.get("reward")?.takeIf { it.isJsonObject }?.asJsonObject?.let { r ->
            rewardYuan = r.get("yuan")?.asLong
            require(rewardYuan != null && rewardYuan!! > 0) { "reward.yuan 須為正整數" }
            rewardTo = r.get("to")?.asString ?: WalletCodes.LIVING
            requireWalletNonCentral(rewardTo, "reward.to")
        }

        var penaltyYuan: Long? = null
        var penaltyFrom = WalletCodes.LIVING
        obj.get("penalty")?.takeIf { it.isJsonObject }?.asJsonObject?.let { p ->
            penaltyYuan = p.get("yuan")?.asLong
            require(penaltyYuan != null && penaltyYuan!! > 0) { "penalty.yuan 須為正整數" }
            penaltyFrom = p.get("from")?.asString ?: WalletCodes.LIVING
            requireWalletNonCentral(penaltyFrom, "penalty.from")
        }

        val planned = parseOptionalWhen(obj, "planned")
            ?: parseOptionalWhen(obj, "appear")
            ?: parseOptionalWhen(obj, "due")
        return WalletCommand.TaskCreate(
            title = title,
            category = category,
            note = obj.get("note")?.asString?.trim().orEmpty(),
            ticketLevel = ticketLevel,
            repeatable = repeatable,
            repeatFreq = repeatFreq,
            repeatN = repeatN,
            rewardYuan = rewardYuan,
            rewardTo = rewardTo,
            penaltyYuan = penaltyYuan,
            penaltyFrom = penaltyFrom,
            planned = planned,
            planId = obj.get("planId")?.asString?.trim()?.ifBlank { null }
        )
    }

    private fun parseCourseUpsert(obj: JsonObject): WalletCommand.CourseUpsert {
        val title = obj.get("title")?.asString?.trim().orEmpty()
        require(title.isNotBlank()) { "title 不可空白" }
        val weekday = obj.get("weekday")?.asInt ?: error("缺少 weekday")
        require(weekday in 1..7) { "weekday 須為 1–7（週一–週日）" }
        val periodStart = obj.get("periodStart")?.asInt ?: error("缺少 periodStart")
        val periodEnd = obj.get("periodEnd")?.asInt ?: periodStart
        require(periodStart in 1..12 && periodEnd in 1..12 && periodEnd >= periodStart) {
            "節次須為 1–12 且 periodEnd≥periodStart"
        }
        val weeks = mutableListOf<Int>()
        obj.get("weeks")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { el ->
            val n = el.asInt
            require(n in 1..40) { "weeks 項目須為 1–40" }
            weeks += n
        }
        return WalletCommand.CourseUpsert(
            title = title,
            weekday = weekday,
            periodStart = periodStart,
            periodEnd = periodEnd,
            location = obj.get("location")?.asString?.trim().orEmpty(),
            weeks = weeks.distinct().sorted()
        )
    }

    private fun parseEventCreate(obj: JsonObject): WalletCommand.EventCreate {
        val title = obj.get("title")?.asString?.trim().orEmpty()
        require(title.isNotBlank()) { "title 不可空白" }
        val date = obj.get("date")?.takeIf { !it.isJsonNull }?.asString?.trim()?.ifBlank { null }
        val week = obj.get("week")?.takeIf { !it.isJsonNull }?.asInt
        val weekday = obj.get("weekday")?.takeIf { !it.isJsonNull }?.asInt
        if (date != null) {
            require(date.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) { "date 須為 yyyy-MM-dd" }
            try {
                java.time.LocalDate.parse(date)
            } catch (_: Exception) {
                error("date 不是有效日期：$date")
            }
        } else {
            require(week != null) { "event.create 須提供 date 或 week+weekday" }
            require(weekday != null && weekday in 1..7) { "weekday 須為 1–7（週一–週日）" }
            require(week in 1..40) { "week 須為 1–40" }
        }
        val time = obj.get("time")?.takeIf { !it.isJsonNull }?.asString?.trim()?.ifBlank { null }
        if (time != null) {
            require(time.matches(Regex("""\d{1,2}:\d{2}"""))) { "time 須為 HH:mm" }
        }
        return WalletCommand.EventCreate(
            title = title,
            date = date,
            week = week,
            weekday = weekday,
            time = time,
            location = obj.get("location")?.asString?.trim().orEmpty(),
            note = obj.get("note")?.asString?.trim().orEmpty()
        )
    }

    private fun parseEventUpdate(obj: JsonObject): WalletCommand.EventUpdate {
        val id = obj.get("id")?.asLong ?: error("缺少 id")
        val date = obj.get("date")?.takeIf { !it.isJsonNull }?.asString?.trim()?.ifBlank { null }
        val week = obj.get("week")?.takeIf { !it.isJsonNull }?.asInt
        val weekday = obj.get("weekday")?.takeIf { !it.isJsonNull }?.asInt
        if (date != null) {
            require(date.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) { "date 須為 yyyy-MM-dd" }
            try {
                java.time.LocalDate.parse(date)
            } catch (_: Exception) {
                error("date 不是有效日期：$date")
            }
        } else if (week != null) {
            require(weekday != null && weekday in 1..7) { "weekday 須為 1–7（週一–週日）" }
            require(week in 1..40) { "week 須為 1–40" }
        }
        val timeEl = obj.get("time")
        val clearTime = timeEl != null && timeEl.isJsonNull
        val time = timeEl?.takeIf { !it.isJsonNull }?.asString?.trim()?.ifBlank { null }
        if (time != null) {
            require(time.matches(Regex("""\d{1,2}:\d{2}"""))) { "time 須為 HH:mm" }
        }
        return WalletCommand.EventUpdate(
            id = id,
            title = obj.get("title")?.takeIf { !it.isJsonNull }?.asString?.trim()?.ifBlank { null },
            date = date,
            week = week,
            weekday = weekday,
            time = time,
            clearTime = clearTime,
            location = obj.get("location")?.takeIf { !it.isJsonNull }?.asString?.trim(),
            note = obj.get("note")?.takeIf { !it.isJsonNull }?.asString?.trim()
        )
    }

    private fun parseTaskUpdate(obj: JsonObject): WalletCommand.TaskUpdate {
        val id = obj.get("id")?.takeIf { !it.isJsonNull }?.asLong
        val titleContains = obj.get("titleContains")?.takeIf { !it.isJsonNull }
            ?.asString?.trim()?.ifBlank { null }
        require(id != null || !titleContains.isNullOrBlank()) {
            "須提供 id 或 titleContains"
        }
        val plannedEl = obj.get("planned") ?: obj.get("appear") ?: obj.get("due")
        val planEl = obj.get("planId")
        val rewardEl = obj.get("reward")
        val penaltyEl = obj.get("penalty")
        var rewardYuan: Long? = null
        var rewardTo: String? = null
        var clearReward = false
        if (rewardEl != null) {
            if (rewardEl.isJsonNull) {
                clearReward = true
            } else {
                val r = rewardEl.asJsonObject
                rewardYuan = r.get("yuan")?.asLong
                    ?: r.get("yuan")?.asDouble?.toLong()
                    ?: error("reward.yuan 必填")
                require(rewardYuan > 0) { "reward.yuan 須為正數" }
                rewardTo = r.get("to")?.asString?.trim() ?: "living"
                requireWalletId(rewardTo!!, "reward.to")
                require(rewardTo != WalletCodes.CENTRAL) { "reward.to 不可為 central" }
            }
        }
        var penaltyYuan: Long? = null
        var penaltyFrom: String? = null
        var clearPenalty = false
        if (penaltyEl != null) {
            if (penaltyEl.isJsonNull) {
                clearPenalty = true
            } else {
                val p = penaltyEl.asJsonObject
                penaltyYuan = p.get("yuan")?.asLong
                    ?: p.get("yuan")?.asDouble?.toLong()
                    ?: error("penalty.yuan 必填")
                require(penaltyYuan > 0) { "penalty.yuan 須為正數" }
                penaltyFrom = p.get("from")?.asString?.trim() ?: "living"
                requireWalletId(penaltyFrom!!, "penalty.from")
                require(penaltyFrom != WalletCodes.CENTRAL) { "penalty.from 不可為 central" }
            }
        }
        val ticketLevel = obj.get("ticketLevel")?.takeIf { !it.isJsonNull }?.asInt
        if (ticketLevel != null) require(ticketLevel in 1..9) { "ticketLevel 須為 1–9" }
        val category = obj.get("category")?.takeIf { !it.isJsonNull }?.asString?.trim()
        if (category != null) {
            require(category in TaskCategories.PRESETS) { "category 無效" }
        }
        return WalletCommand.TaskUpdate(
            id = id,
            titleContains = titleContains,
            title = obj.get("title")?.takeIf { !it.isJsonNull }?.asString?.trim()?.ifBlank { null },
            category = category,
            note = obj.get("note")?.takeIf { !it.isJsonNull }?.asString?.trim(),
            ticketLevel = ticketLevel,
            rewardYuan = rewardYuan,
            rewardTo = rewardTo,
            clearReward = clearReward,
            penaltyYuan = penaltyYuan,
            penaltyFrom = penaltyFrom,
            clearPenalty = clearPenalty,
            repeatable = obj.get("repeatable")?.takeIf { !it.isJsonNull }?.asBoolean,
            repeatFreq = obj.get("repeat")?.takeIf { it.isJsonObject }
                ?.asJsonObject?.get("freq")?.asString?.trim(),
            repeatN = obj.get("repeat")?.takeIf { it.isJsonObject }
                ?.asJsonObject?.get("n")?.takeIf { !it.isJsonNull }?.asInt,
            planned = if (plannedEl != null && !plannedEl.isJsonNull && plannedEl.isJsonObject) {
                parseWhen(plannedEl.asJsonObject, "planned")
            } else null,
            clearPlanned = plannedEl != null && plannedEl.isJsonNull,
            planId = planEl?.takeIf { !it.isJsonNull }?.asString?.trim()?.ifBlank { null },
            clearPlanId = planEl != null && planEl.isJsonNull
        )
    }

    private fun parseIncomeRecord(obj: JsonObject): WalletCommand.IncomeRecord {
        val yuan = obj.get("yuan")?.asDouble ?: error("缺少 yuan")
        require(yuan > 0) { "yuan 須為正數" }
        val defer = obj.get("deferCentral")?.takeIf { !it.isJsonNull }?.asBoolean ?: true
        return WalletCommand.IncomeRecord(
            yuan = yuan,
            note = obj.get("note")?.asString?.trim().orEmpty(),
            deferCentral = defer
        )
    }

    private fun parseRewardGrant(obj: JsonObject): WalletCommand.RewardGrant {
        val nonce = obj.get("nonce")?.asString?.trim().orEmpty()
        require(nonce.isNotBlank()) { "nonce 不可空白" }
        val yuan = obj.get("yuan")?.takeIf { !it.isJsonNull }?.asDouble
        if (yuan != null) require(yuan > 0) { "yuan 須為正數" }
        val to = obj.get("to")?.takeIf { !it.isJsonNull }?.asString?.trim()?.ifBlank { null }
        if (to != null) {
            requireWalletId(to, "to")
            require(to != WalletCodes.CENTRAL) { "to 不可為 central" }
        }
        val ticket = obj.get("ticket")?.takeIf { it.isJsonObject }?.asJsonObject
        val ticketKind = ticket?.get("kind")?.asString?.trim()?.uppercase()
        val ticketN = ticket?.get("n")?.asInt ?: 0
        if (ticketKind != null) {
            require(ticketN > 0) { "ticket.n 須為正整數" }
        }
        require(yuan != null || ticketKind != null) { "須提供 yuan 或 ticket" }
        if (yuan != null) require(!to.isNullOrBlank()) { "現金獎須指定 to" }
        return WalletCommand.RewardGrant(
            nonce = nonce,
            yuan = yuan,
            to = to,
            ticketKind = ticketKind,
            ticketN = ticketN,
            note = obj.get("note")?.asString?.trim().orEmpty()
        )
    }

    private fun parseSemesterSet(obj: JsonObject): WalletCommand.SemesterSet {
        val week1 = obj.get("week1Monday")?.asString?.trim().orEmpty()
        require(week1.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) { "week1Monday 須為 yyyy-MM-dd" }
        try {
            java.time.LocalDate.parse(week1)
        } catch (_: Exception) {
            error("week1Monday 不是有效日期：$week1")
        }
        val weeks = obj.get("weeks")?.asInt ?: 18
        require(weeks in 1..40) { "weeks 須為 1–40" }
        return WalletCommand.SemesterSet(week1Monday = week1, weeks = weeks)
    }

    private fun parsePlanUpsert(obj: JsonObject): WalletCommand.PlanUpsert {
        val id = obj.get("id")?.asString?.trim().orEmpty()
        com.gino.mywallet.data.entity.PlanIds.requireValid(id)
        val title = obj.get("title")?.asString?.trim().orEmpty().ifBlank { id }
        return WalletCommand.PlanUpsert(
            id = id,
            title = title,
            note = obj.get("note")?.asString?.trim().orEmpty()
        )
    }

    private fun parsePlanImport(obj: JsonObject): WalletCommand.PlanImport {
        val upsert = parsePlanUpsert(obj)
        val itemsArr = obj.getAsJsonArray("items")
            ?: error("plan.import 需要 items 陣列")
        require(itemsArr.size() <= CommandSchema.MAX_PLAN_ITEMS) {
            "plan.import items 不可超過 ${CommandSchema.MAX_PLAN_ITEMS}"
        }
        val items = itemsArr.mapIndexed { i, el ->
            try {
                parseTaskCreate(el.asJsonObject)
            } catch (e: Exception) {
                error("items[$i]：${e.message}")
            }
        }
        return WalletCommand.PlanImport(
            id = upsert.id,
            title = upsert.title,
            note = upsert.note,
            items = items
        )
    }

    private fun parseOptionalWhen(obj: JsonObject, field: String): CommandWhen? {
        val el = obj.get(field) ?: return null
        if (el.isJsonNull) return null
        require(el.isJsonObject) { "$field 須為物件" }
        return parseWhen(el.asJsonObject, field)
    }

    private fun parseWhen(obj: JsonObject, field: String): CommandWhen {
        val date = obj.get("date")?.takeIf { !it.isJsonNull }?.asString?.trim()?.ifBlank { null }
        val week = obj.get("week")?.takeIf { !it.isJsonNull }?.asInt
        val weekday = obj.get("weekday")?.takeIf { !it.isJsonNull }?.asInt
        val time = obj.get("time")?.takeIf { !it.isJsonNull }?.asString?.trim()?.ifBlank { null }
        if (date != null) {
            require(date.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) { "$field.date 須為 yyyy-MM-dd" }
            try {
                java.time.LocalDate.parse(date)
            } catch (_: Exception) {
                error("$field.date 不是有效日期：$date")
            }
        } else {
            require(week != null) { "$field 須提供 date 或 week" }
            require(weekday != null && weekday in 1..7) { "$field.weekday 須為 1–7" }
            require(week in 1..40) { "$field.week 無效" }
        }
        if (time != null) {
            require(time.matches(Regex("""\d{1,2}:\d{2}"""))) { "$field.time 須為 HH:mm" }
        }
        return CommandWhen(date = date, week = week, weekday = weekday, time = time)
    }

    private fun requireWalletNonCentral(id: String, field: String) {
        require(id != WalletCodes.CENTRAL) { "$field 不可為 central" }
        require(
            id in listOf(
                WalletCodes.LIVING, WalletCodes.CARD, WalletCodes.INVEST, WalletCodes.STUDY,
                WalletCodes.BCI, WalletCodes.VTUBER, WalletCodes.ACG, WalletCodes.SOCIAL
            )
        ) { "$field 錢包 id 無效：$id" }
    }

    fun formatResult(result: CommandRunResult): String {
        val root = JsonObject()
        root.addProperty("schema", result.schema)
        root.addProperty("ok", result.ok)
        root.addProperty("mode", result.mode)
        root.addProperty("applied", result.applied)
        root.addProperty("failed", result.failed)
        val arr = JsonArray()
        result.results.forEach { item ->
            val o = JsonObject()
            o.addProperty("index", item.index)
            o.addProperty("op", item.op)
            o.addProperty("status", item.status)
            o.addProperty("message", item.message)
            item.id?.let { o.addProperty("id", it) }
            item.title?.let { o.addProperty("title", it) }
            arr.add(o)
        }
        root.add("results", arr)
        return gson.toJson(root)
    }
}
