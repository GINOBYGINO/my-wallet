package com.gino.mywallet.domain.game

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs

enum class WordTier {
    EASY,
    NORMAL,
    HARD;

    fun prizeYuan(): Int = when (this) {
        EASY -> GamePrizes.WORDLE_EASY_YUAN
        NORMAL -> GamePrizes.WORDLE_NORMAL_YUAN
        HARD -> GamePrizes.WORDLE_HARD_YUAN
    }
}

data class AnswerEntry(
    val word: String,
    val tier: WordTier,
    val zh: String
)

/** 每日五字（Wordle 玩法）：中午 12:00 重置，不可用券，每天一題 */
object DailyWord {
    const val WORD_LEN = 5
    const val MAX_GUESSES = 6

    private val gson = Gson()

    private data class AnswerJson(
        val word: String = "",
        val tier: String = "NORMAL",
        val zh: String = ""
    )

    private val answerEntries: List<AnswerEntry> by lazy { loadAnswers() }
    private val allowedSet: Set<String> by lazy { loadAllowed() }

    /** 答案詞（僅字串，相容舊測試／除錯） */
    val ANSWERS: List<String> get() = answerEntries.map { it.word }

    /** 允許猜測（含答案） */
    val ALLOWED: Set<String> get() = allowedSet

    private fun openResource(path: String) =
        checkNotNull(
            DailyWord::class.java.classLoader?.getResourceAsStream(path)
                ?: DailyWord::class.java.getResourceAsStream("/$path")
        ) { "缺少詞表資源：$path" }

    private fun loadAnswers(): List<AnswerEntry> {
        val raw = openResource("wordle/answers.json").bufferedReader().use { it.readText() }
        val type = object : TypeToken<List<AnswerJson>>() {}.type
        val list: List<AnswerJson> = gson.fromJson(raw, type)
        return list.mapNotNull { row ->
            val word = row.word.trim().uppercase()
            if (word.length != WORD_LEN) return@mapNotNull null
            val tier = runCatching { WordTier.valueOf(row.tier.trim().uppercase()) }
                .getOrDefault(WordTier.NORMAL)
            AnswerEntry(word = word, tier = tier, zh = row.zh.trim().ifBlank { word })
        }.distinctBy { it.word }
    }

    private fun loadAllowed(): Set<String> {
        val fromFile = openResource("wordle/allowed.txt").bufferedReader().useLines { lines ->
            lines.map { it.trim().uppercase() }
                .filter { it.length == WORD_LEN && it.all { c -> c in 'A'..'Z' } }
                .toSet()
        }
        return fromFile + answerEntries.map { it.word }
    }

    /**
     * 週期 id：當日 12:00～次日 12:00。
     * 12:00 前算「昨天的題」（以昨日起點日期字串）。
     */
    fun periodId(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        val noon = LocalTime.of(12, 0)
        val startDate = if (now.toLocalTime() < noon) {
            now.toLocalDate().minusDays(1)
        } else {
            now.toLocalDate()
        }
        return startDate.toString()
    }

    fun nextResetAt(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        val noon = LocalTime.of(12, 0)
        val next = if (now.toLocalTime() < noon) {
            LocalDateTime.of(now.toLocalDate(), noon)
        } else {
            LocalDateTime.of(now.toLocalDate().plusDays(1), noon)
        }
        return next.atZone(zone).toInstant().toEpochMilli()
    }

    fun entryForPeriod(periodId: String): AnswerEntry {
        val list = answerEntries
        require(list.isNotEmpty()) { "答案詞表為空" }
        val idx = abs(periodId.hashCode()) % list.size
        return list[idx]
    }

    fun lookupAnswer(word: String): AnswerEntry? {
        val key = normalizeGuess(word)
        return answerEntries.firstOrNull { it.word == key }
    }

    fun answerForPeriod(periodId: String): String = entryForPeriod(periodId).word

    fun newRound(periodId: String): DailyWordState {
        val entry = entryForPeriod(periodId)
        return DailyWordState(
            periodId = periodId,
            answer = entry.word,
            answerZh = entry.zh,
            tier = entry.tier.name,
            prizeYuan = entry.tier.prizeYuan(),
            guesses = emptyList(),
            phase = DailyWordPhase.PLAYING,
            rewarded = false
        )
    }

    fun normalizeGuess(raw: String): String =
        raw.trim().uppercase().filter { it in 'A'..'Z' }

    fun isValidGuess(word: String): Boolean =
        word.length == WORD_LEN && normalizeGuess(word) in allowedSet

    /** Wordle 染色：綠正確位、黃錯位有字母、灰無 */
    fun evaluate(guess: String, answer: String): List<LetterMark> {
        require(guess.length == WORD_LEN && answer.length == WORD_LEN)
        val marks = Array(WORD_LEN) { LetterMark.ABSENT }
        val remaining = IntArray(26)
        for (i in 0 until WORD_LEN) {
            if (guess[i] == answer[i]) {
                marks[i] = LetterMark.CORRECT
            } else {
                remaining[answer[i] - 'A']++
            }
        }
        for (i in 0 until WORD_LEN) {
            if (marks[i] == LetterMark.CORRECT) continue
            val c = guess[i] - 'A'
            if (remaining[c] > 0) {
                marks[i] = LetterMark.PRESENT
                remaining[c]--
            }
        }
        return marks.toList()
    }

    fun submitGuess(state: DailyWordState, raw: String): DailyWordState {
        require(state.phase == DailyWordPhase.PLAYING) { "本局已結束" }
        val guess = normalizeGuess(raw)
        require(isValidGuess(guess)) { "不是有效五字母單字" }
        val marks = evaluate(guess, state.answer)
        val guesses = state.guesses + DailyWordGuess(guess, marks)
        val won = guess == state.answer
        val lost = !won && guesses.size >= MAX_GUESSES
        return state.copy(
            guesses = guesses,
            phase = when {
                won -> DailyWordPhase.WON
                lost -> DailyWordPhase.LOST
                else -> DailyWordPhase.PLAYING
            }
        )
    }
}

enum class LetterMark { CORRECT, PRESENT, ABSENT }

enum class DailyWordPhase { PLAYING, WON, LOST }

data class DailyWordGuess(
    val word: String,
    val marks: List<LetterMark>
)

data class DailyWordState(
    val periodId: String = "",
    val answer: String = "",
    val answerZh: String = "",
    val tier: String = WordTier.NORMAL.name,
    val prizeYuan: Int = GamePrizes.WORDLE_NORMAL_YUAN,
    val guesses: List<DailyWordGuess> = emptyList(),
    val phase: DailyWordPhase = DailyWordPhase.PLAYING,
    val rewarded: Boolean = false
)
