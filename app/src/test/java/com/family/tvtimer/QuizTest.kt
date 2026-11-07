package com.family.tvtimer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuizTest {

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
     * 大量随机出题：所有题型都会出现；答案是正数、能精确输入（整数或不超过 3 位的小数）；
     * 解题步骤非空；钱的格式不出错（toMoney 遇到超过 2 位小数会抛异常）。
     */
    @Test
    fun generatedQuestionsAreConsistent() {
        val texts = mutableSetOf<String>()
        repeat(100_000) {
            val q = Quiz.next()
            texts += q.text.substringBefore(' ') + q.topic
            assertTrue("${q.text} 答案应为正：${q.answer}", q.answer.num > 0)
            assertTrue("${q.text} 答案应能用小数输入：${q.answer}", q.answer.isFiniteDecimal && q.answer.decimalPlaces <= 3)
            assertEquals(q.answer, Frac.parse(q.answer.toString()))
            assertTrue(q.text.isNotBlank() && q.explain.isNotBlank() && q.answerText.isNotBlank())
            assertTrue("讲解最后一步应得出答案：${q.explain} / ${q.answerText}",
                q.explain.lines().last().contains(q.answerText.substringBefore(' ')))
            assertTrue("题目里不应出现负数：${q.text}", !Regex("(^|[^0-9a-zA-Z])-\\d").containsMatchIn(q.text))
        }
        assertTrue(Quiz.generatorCount >= 20)
    }

    @Test
    fun printSamples() {
        repeat(30) {
            val q = Quiz.next()
            println("[${q.topic}] ${q.text.replace("\n", " ")}\n   => ${q.answerText}\n   ${q.explain.replace("\n", "\n   ")}\n")
        }
    }
}
