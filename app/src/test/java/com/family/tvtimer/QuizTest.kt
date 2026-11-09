package com.family.tvtimer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class QuizTest {

    companion object {
        /** 单元测试的工作目录是 app/，直接读源码里的题库 */
        private fun lines(dir: String): List<String> =
            File(dir).listFiles { f -> f.name.endsWith(".txt") }.orEmpty()
                .sortedBy { it.name }.flatMap { it.readLines(Charsets.UTF_8) }

        /** 搁置的自编题库（仓库 old_banks/），仍然检查格式，以后可以再接回来 */
        val oldEnglishP5 = FillBank("Primary 5", "english")
        val oldEnglishSec2 = FillBank("Secondary 2", "english_sec2")
        val oldChineseP5 = ChoiceBank("Primary 5", "chinese_p5")
        val oldChineseSec2 = ChoiceBank("Secondary 2", "chinese_sec2")

        @JvmStatic
        @BeforeClass
        fun loadBanks() {
            for (b in Banks.all) b.load(lines("src/main/assets/${b.dir}"))
            oldEnglishP5.load(lines("../old_banks/english"))
            oldEnglishSec2.load(lines("../old_banks/english_sec2"))
            oldChineseP5.load(lines("../old_banks/chinese_p5"))
            oldChineseSec2.load(lines("../old_banks/chinese_sec2"))
        }
    }

    @Test
    fun parseAcceptsEquivalentForms() {
        assertEquals(Frac.of(3, 4), Frac.parse("3/4"))
        assertEquals(Frac.of(3, 4), Frac.parse("0.75"))
        assertEquals(Frac.of(3, 4), Frac.parse("6/8"))
        assertEquals(Frac.of(5), Frac.parse("5.0"))
        assertEquals(Frac.of(1, 2), Frac.parse(".5"))
        assertEquals(Frac.of(-7), Frac.parse("-7"))
        assertEquals(Frac.of(-3, 4), Frac.parse("-3/4"))
        assertEquals(Frac.of(-3, 4), Frac.parse("-0.75"))
        assertNull(Frac.parse(""))
        assertNull(Frac.parse("1/0"))
        assertNull(Frac.parse("1/2/3"))
        assertNull(Frac.parse("1..2"))
        assertNull(Frac.parse("/"))
        assertNull(Frac.parse("-"))
    }

    @Test
    fun formatting() {
        assertEquals("0.3", (Frac.dec(1, 1) + Frac.dec(2, 1)).toString())
        assertEquals("$4.50", Frac.of(9, 2).toMoney())
        assertEquals("$45", Frac.of(45).toMoney())
        assertEquals("5 3/4", Frac.of(23, 4).toMixedString())
        assertEquals("3/4", Frac.of(3, 4).toMixedString())
    }

    private fun checkMath(q: Question, allowNegative: Boolean) {
        val a = q.answer!!
        assertEquals(Subject.MATH, q.subject)
        if (!allowNegative) {
            // P5：答案为正，且是整数或不超过 3 位的小数（标准答案里可能带 $ 和单位）
            assertTrue("${q.text} 答案应为正：$a", a.num > 0)
            assertTrue("${q.text} 答案应能用小数输入：$a", a.isFiniteDecimal && a.decimalPlaces <= 3)
        } else {
            // Sec 2：标准答案就是纯数字/分数，必须能原样输入并判对
            assertTrue("${q.text} 标准答案应判为对：${q.answerText}", q.isCorrect(q.answerText))
        }
        assertTrue(q.isCorrect(a.toString()))
        assertTrue(q.isCorrect(a.toFracString()))
        assertTrue(q.text.isNotBlank() && q.explain.isNotBlank())
        assertTrue("讲解最后一步应得出答案：${q.explain} / ${q.answerText}",
            q.explain.lines().last().contains(q.answerText.substringBefore(' ')))
    }

    /** P5 数学：大量随机出题，答案为正、整洁、讲解得出答案 */
    @Test
    fun p5MathIsConsistent() {
        repeat(100_000) {
            val q = Quiz.nextMath()
            assertEquals("Primary 5", q.level)
            checkMath(q, allowNegative = false)
            assertTrue("题目里不应出现负数：${q.text}", !Regex("(^|[^0-9a-zA-Z])-\\d").containsMatchIn(q.text))
        }
        assertTrue(Quiz.generatorCount >= 20)
    }

    /** Sec 2 数学：答案可以是负数或分数，但必须精确、可输入、讲解得出答案 */
    @Test
    fun sec2MathIsConsistent() {
        repeat(100_000) {
            val q = MathSec2.next()
            assertEquals("Secondary 2", q.level)
            checkMath(q, allowNegative = true)
        }
        assertTrue(MathSec2.generatorCount >= 15)
    }

    private fun checkFillBank(bank: FillBank, minSize: Int) {
        val items = bank.items
        assertTrue("${bank.dir} 只有 ${items.size} 题", items.size >= minSize)
        val dup = items.groupBy { it.sentence }.filter { it.value.size > 1 }.keys
        assertTrue("${bank.dir} 重复的题：$dup", dup.isEmpty())
        for (it in items) {
            for (a in it.answers) assertTrue("答案只能是一个单词：${it.sentence} → $a", Regex("[A-Za-z']+").matches(a))
            if (it.code in "TSWUA") assertTrue("这类题要在括号里给出原词：${it.sentence}", it.sentence.contains("("))
            assertFalse("句子里不应有多余的下划线：${it.sentence}", it.sentence.contains("____"))
            assertTrue(it.explain.isNotBlank())
            val q = bank.toQuestion(it)
            assertEquals(Subject.ENGLISH, q.subject)
            assertEquals(bank.level, q.level)
            assertTrue("空格应被替换：${q.text}", q.text.startsWith(it.sentence.substringBefore("___") + bank.blank(it)))
            for (a in it.answers) {
                assertTrue(q.isCorrect(a))
                assertTrue(q.isCorrect(" ${a.uppercase()} "))
            }
            assertFalse(q.isCorrect(""))
            assertFalse(q.isCorrect(it.answers.first() + "x"))
        }
    }

    @Test
    fun oldEnglishBanksAreValid() {
        checkFillBank(oldEnglishP5, 600)
        checkFillBank(oldEnglishSec2, 200)
        val overlap = oldEnglishP5.items.map { it.sentence }.toSet() intersect oldEnglishSec2.items.map { it.sentence }.toSet()
        assertTrue("P5 和 Sec 2 有重复的题：$overlap", overlap.isEmpty())
    }

    private fun checkChoiceBank(bank: ChoiceBank, minSize: Int) {
        val items = bank.items
        assertTrue("${bank.dir} 只有 ${items.size} 题", items.size >= minSize)
        val dup = items.groupBy { it.stem }.filter { it.value.size > 1 }.keys
        assertTrue("${bank.dir} 重复的题：$dup", dup.isEmpty())
        for (it in items) {
            for (o in it.wrong + it.answer) assertTrue("选项太长（电视按钮放不下）：${it.stem} → $o", o.length <= 22)
            assertTrue("题干太长：${it.stem}", it.stem.length <= 130)
            val q = bank.toQuestion(it, kotlin.random.Random(1))
            assertEquals(Subject.CHINESE, q.subject)
            assertEquals(4, q.options.size)
            assertTrue(q.options.contains(it.answer))
            assertTrue(q.isCorrect(it.answer))
            for (w in it.wrong) assertFalse(q.isCorrect(w))
        }
    }

    @Test
    fun oldChineseBanksAreValid() {
        checkChoiceBank(oldChineseP5, 300)
        checkChoiceBank(oldChineseSec2, 150)
    }

    /** 真题题库：每一行都要能解析，标准答案判对、干扰项判错，长度放得进电视界面，不重复 */
    @Test
    fun paperBanksAreValid() {
        val minSize = mapOf(
            Banks.englishP5 to 150, Banks.mathP5 to 100, Banks.chineseP5 to 100,
            Banks.englishSec2 to 50, Banks.mathSec2 to 50, Banks.scienceSec2 to 80,
        )
        for (bank in Banks.all) {
            val items = bank.items
            assertTrue("${bank.dir} 只有 ${items.size} 题", items.size >= minSize.getValue(bank))
            val dup = items.groupBy { it.text + it.answers }.filter { it.value.size > 1 }.keys
            assertTrue("${bank.dir} 重复的题：$dup", dup.isEmpty())
            for (it in items) {
                val where = "${bank.dir} [${it.source}] ${it.text.take(60)}"
                assertTrue("题目太长（电视上看不完）：$where", it.text.length <= 700)
                assertTrue("讲解太长：$where", it.explain.length <= 700)
                val q = bank.toQuestion(it, kotlin.random.Random(1))
                assertEquals(bank.subject, q.subject)
                assertEquals(bank.level, q.level)
                assertEquals(it.kind, q.kind)
                for (a in it.answers) assertTrue("标准答案应判为对：$where → $a", q.isCorrect(a))
                assertFalse(q.isCorrect(""))
                when (it.kind) {
                    Kind.CHOICE -> {
                        val limit = if (bank.subject == Subject.CHINESE) 40 else 100
                        for (o in q.options) assertTrue("选项太长：$where → $o", o.length <= limit)
                        for (w in it.wrong) assertFalse("干扰项不应判对：$where → $w", q.isCorrect(w))
                    }
                    Kind.WORD -> {
                        assertTrue("填单词题只用于英文：$where", bank.subject == Subject.ENGLISH)
                        assertTrue("答案太长（输入框 16 个字母）：$where", it.answers.all { a -> a.length <= 16 })
                        assertFalse(q.isCorrect(it.answers.first() + "x"))
                    }
                    Kind.NUMBER -> {
                        assertTrue("答案太长：$where", it.answers.first().length <= 16)
                        assertTrue(q.isCorrect(q.answer!!.toFracString()))
                    }
                }
            }
        }
        assertTrue("华文不应有填单词/填数字题", Banks.chineseP5.items.all { it.kind == Kind.CHOICE })
    }

    /** 出题比例：P5 : Sec 2 = 3 : 1；P5 英文 : 数学 : 华文 = 2 : 1 : 1；Sec 2 英文 : 数学 : 科学 = 2 : 1 : 1 */
    @Test
    fun mixMatchesRequestedRatio() {
        val n = 160_000
        val counts = (1..n).map { Quiz.next() }.groupingBy { "${it.level}|${it.subject}" }.eachCount()
        fun share(level: String, s: Subject) = (counts["$level|$s"] ?: 0).toDouble() / n
        val expected = mapOf(
            ("Primary 5" to Subject.ENGLISH) to 6, ("Primary 5" to Subject.MATH) to 3, ("Primary 5" to Subject.CHINESE) to 3,
            ("Secondary 2" to Subject.ENGLISH) to 2, ("Secondary 2" to Subject.MATH) to 1, ("Secondary 2" to Subject.SCIENCE) to 1,
        )
        for ((k, w) in expected) {
            val got = share(k.first, k.second)
            assertEquals("$k 比例", w / 16.0, got, 0.006)
        }
    }

    @Test
    fun printSamples() {
        for (b in Banks.all) {
            val bySource = b.items.groupingBy { it.source }.eachCount()
            println("${b.dir}: ${b.items.size} 题  ${b.items.groupingBy { it.kind }.eachCount()}  $bySource")
        }
        repeat(30) {
            val q = Quiz.next()
            println("[${q.level} ${q.subject} · ${q.topic} · ${q.source}] ${q.text.replace("\n", " ")}" +
                (if (q.isChoice) "  选项：${q.options}" else "") +
                "\n   => ${q.answerText}\n   ${q.explain.replace("\n", "\n   ")}\n")
        }
    }
}
