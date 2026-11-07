package com.family.tvtimer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class QuizTest {

    companion object {
        /** 单元测试的工作目录是 app/，直接读源码里的英文题库 */
        private val englishFiles = File("src/main/assets/english").listFiles { f -> f.name.endsWith(".txt") }!!.sorted()

        @JvmStatic
        @BeforeClass
        fun loadEnglish() {
            English.load(englishFiles.flatMap { it.readLines() })
        }
    }

    @Test
    fun parseAcceptsEquivalentForms() {
        assertEquals(Frac.of(3, 4), Frac.parse("3/4"))
        assertEquals(Frac.of(3, 4), Frac.parse("0.75"))
        assertEquals(Frac.of(3, 4), Frac.parse("6/8"))
        assertEquals(Frac.of(5), Frac.parse("5.0"))
        assertEquals(Frac.of(1, 2), Frac.parse(".5"))
        assertNull(Frac.parse(""))
        assertNull(Frac.parse("1/0"))
        assertNull(Frac.parse("1/2/3"))
        assertNull(Frac.parse("1..2"))
        assertNull(Frac.parse("/"))
    }

    @Test
    fun formatting() {
        assertEquals("0.3", (Frac.dec(1, 1) + Frac.dec(2, 1)).toString())
        assertEquals("$4.50", Frac.of(9, 2).toMoney())
        assertEquals("$45", Frac.of(45).toMoney())
        assertEquals("5 3/4", Frac.of(23, 4).toMixedString())
        assertEquals("3/4", Frac.of(3, 4).toMixedString())
    }

    /**
     * 大量随机出数学题：答案是正数、能精确输入（整数或不超过 3 位的小数）；
     * 解题步骤非空；钱的格式不出错（toMoney 遇到超过 2 位小数会抛异常）。
     */
    @Test
    fun generatedMathIsConsistent() {
        repeat(100_000) {
            val q = Quiz.nextMath()
            val a = q.answer!!
            assertFalse(q.isEnglish)
            assertTrue("${q.text} 答案应为正：$a", a.num > 0)
            assertTrue("${q.text} 答案应能用小数输入：$a", a.isFiniteDecimal && a.decimalPlaces <= 3)
            assertTrue(q.isCorrect(a.toString()))
            assertTrue(q.text.isNotBlank() && q.explain.isNotBlank() && q.answerText.isNotBlank())
            assertTrue("讲解最后一步应得出答案：${q.explain} / ${q.answerText}",
                q.explain.lines().last().contains(q.answerText.substringBefore(' ')))
            assertTrue("题目里不应出现负数：${q.text}", !Regex("(^|[^0-9a-zA-Z])-\\d").containsMatchIn(q.text))
        }
        assertTrue(Quiz.generatorCount >= 20)
    }

    /** 英文题库：格式正确、答案只含字母和撇号、需要括号提示的题都有提示、没有重复题 */
    @Test
    fun englishBankIsValid() {
        val items = English.items
        val dup = items.groupBy { it.sentence }.filter { it.value.size > 1 }.keys
        assertTrue("重复的题：$dup", dup.isEmpty())
        for (it in items) {
            for (a in it.answers) assertTrue("答案只能是一个单词：${it.sentence} → $a", Regex("[A-Za-z']+").matches(a))
            if (it.code in "TSWUA") assertTrue("这类题要在括号里给出原词：${it.sentence}", it.sentence.contains("("))
            assertTrue(it.explain.isNotBlank())
            val q = English.toQuestion(it)
            assertTrue(q.isEnglish)
            assertTrue("空格应被替换：${q.text}", q.text.startsWith(it.sentence.substringBefore("___") + English.blank(it)))
            for (a in it.answers) {
                assertTrue(q.isCorrect(a))
                assertTrue(q.isCorrect(" ${a.uppercase()} "))
            }
            assertFalse(q.isCorrect(""))
            assertFalse(q.isCorrect(it.answers.first() + "x"))
        }
    }

    /** 英文题量要是数学题型的 5 倍以上；混合出题时英文约占 5/6 */
    @Test
    fun englishShareIsLargeEnough() {
        assertTrue("英文题 ${English.items.size} 道", English.items.size >= 5 * Quiz.generatorCount)
        val english = (1..60_000).count { Quiz.next().isEnglish }
        val share = english / 60_000.0
        assertTrue("英文比例 $share", share in 0.80..0.87)
    }

    @Test
    fun englishHintShowsFirstLetter() {
        val item = English.items.first { it.code == "P" && it.answers.first() == "of" }
        val q = English.toQuestion(item)
        assertTrue(q.text, q.text.contains("o _"))
        assertNotNull(q.text)
    }

    @Test
    fun printSamples() {
        println("English items: ${English.items.size}, maths types: ${Quiz.generatorCount}")
        println(English.items.groupingBy { it.code }.eachCount())
        repeat(20) {
            val q = Quiz.next()
            println("[${q.topic}] ${q.text.replace("\n", " ")}\n   => ${q.answerText}\n   ${q.explain.replace("\n", "\n   ")}\n")
        }
    }
}
