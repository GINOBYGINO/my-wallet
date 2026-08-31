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
        return CommandPlan(schema = schema, mode = mode, note = note, commands = commands)
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
            "task.delete" -> {
                val titleContains = obj.get("titleContains")?.asString?.trim().orEmpty()
                require(titleContains.isNotBlank()) { "titleContains 不可空白" }
                val status = obj.get("status")?.asString?.uppercase() ?: "OPEN"
                WalletCommand.TaskDelete(titleContains = titleContains, status = status)
            }
            else -> error("未知 op：$op")
        }
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
            penaltyFrom = penaltyFrom
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
        return WalletCommand.CourseUpsert(
            title = title,
            weekday = weekday,
            periodStart = periodStart,
            periodEnd = periodEnd,
            location = obj.get("location")?.asString?.trim().orEmpty()
        )
    }

    private fun parseEventCreate(obj: JsonObject): WalletCommand.EventCreate {
        val title = obj.get("title")?.asString?.trim().orEmpty()
        require(title.isNotBlank()) { "title 不可空白" }
        val date = obj.get("date")?.asString?.trim().orEmpty()
        require(date.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) {
            "date 須為 yyyy-MM-dd"
        }
        // 驗證可解析
        try {
            java.time.LocalDate.parse(date)
        } catch (_: Exception) {
            error("date 不是有效日期：$date")
        }
        val time = obj.get("time")?.takeIf { !it.isJsonNull }?.asString?.trim()?.ifBlank { null }
        if (time != null) {
            require(time.matches(Regex("""\d{1,2}:\d{2}"""))) { "time 須為 HH:mm" }
        }
        return WalletCommand.EventCreate(
            title = title,
            date = date,
            time = time,
            location = obj.get("location")?.asString?.trim().orEmpty(),
            note = obj.get("note")?.asString?.trim().orEmpty()
        )
    }

    private fun requireWalletNonCentral(id: String, field: String) {
        require(id != WalletCodes.CENTRAL) { "$field 不可為 central" }
        require(
            id in listOf(
                WalletCodes.LIVING, WalletCodes.INVEST, WalletCodes.STUDY,
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
