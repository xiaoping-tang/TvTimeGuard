package com.family.tvtimer

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import kotlin.random.Random

/** 精确的分数，用来出题和判对错，避免小数误差（0.1 + 0.2 之类） */
@ConsistentCopyVisibility
data class Frac private constructor(val num: Long, val den: Long) : Comparable<Frac> {

    operator fun plus(o: Frac) = of(num * o.den + o.num * den, den * o.den)
    operator fun minus(o: Frac) = of(num * o.den - o.num * den, den * o.den)
    operator fun times(o: Frac) = of(num * o.num, den * o.den)
    operator fun div(o: Frac) = of(num * o.den, den * o.num)
    operator fun plus(n: Int) = this + of(n)
    operator fun minus(n: Int) = this - of(n)
    operator fun times(n: Int) = this * of(n)
    operator fun div(n: Int) = this / of(n)
    override fun compareTo(other: Frac) = (num * other.den).compareTo(other.num * den)

    val isInt get() = den == 1L

    /** 分母只含 2 和 5，能写成有限小数 */
    val isFiniteDecimal: Boolean
        get() {
            var d = den
            while (d % 2 == 0L) d /= 2
            while (d % 5 == 0L) d /= 5
            return d == 1L
        }

    /** 小数位数（只对有限小数有意义） */
    val decimalPlaces: Int
        get() = BigDecimal(num).divide(BigDecimal(den)).stripTrailingZeros().scale().coerceAtLeast(0)

    fun toDecimalString(): String =
        BigDecimal(num).divide(BigDecimal(den)).stripTrailingZeros().toPlainString()

    fun toFracString() = if (isInt) "$num" else "$num/$den"

    /** 带分数：5 3/4 */
    fun toMixedString() = if (isInt || num < den) toFracString() else "${num / den} ${num % den}/$den"

    /** 钱：$45、$4.50（超过 2 位小数会抛异常，测试能发现） */
    fun toMoney(): String = "$" + if (isInt) "$num"
    else BigDecimal(num).divide(BigDecimal(den)).setScale(2, RoundingMode.UNNECESSARY).toPlainString()

    override fun toString() = if (isFiniteDecimal) toDecimalString() else toFracString()

    companion object {
        fun of(n: Long, d: Long = 1): Frac {
            require(d != 0L)
            val g = gcd(Math.abs(n), Math.abs(d)).coerceAtLeast(1)
            val s = if (d < 0) -1 else 1
            return Frac(s * n / g, s * d / g)
        }

        fun of(n: Int, d: Int = 1) = of(n.toLong(), d.toLong())

        /** 小数：dec(1234, 2) = 12.34 */
        fun dec(unscaled: Int, scale: Int) = of(unscaled.toLong(), pow10(scale))

        fun pow10(n: Int): Long = (1..n).fold(1L) { a, _ -> a * 10 }

        /** 解析小孩输入的答案：整数、小数（3.5）或分数（3/4） */
        fun parse(s: String): Frac? = runCatching {
            val t = s.trim()
            if (t.isEmpty()) return null
            if ('/' in t) {
                val parts = t.split('/').map { it.trim() }
                if (parts.size != 2) return null
                val (a, b) = parts
                if (b.toLong() == 0L) return null
                of(a.toLong(), b.toLong())
            } else {
                val bd = BigDecimal(t)
                if (bd.scale() <= 0) of(bd.toBigIntegerExact().toLong(), 1)
                else of(bd.unscaledValue().toLong(), BigInteger.TEN.pow(bd.scale()).toLong())
            }
        }.getOrNull()

        tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
        fun lcm(a: Long, b: Long) = a / gcd(a, b) * b
    }
}

enum class Subject(val label: String) { ENGLISH("English"), MATH("Maths"), CHINESE("华文"), SCIENCE("Science") }

/** 作答方式：数字键盘、字母键盘、四选一 */
enum class Kind { NUMBER, WORD, CHOICE }

/**
 * 一道题。answerText 是显示给小孩看的标准答案，explain 是答错后显示的解题步骤/解释。
 *  - 填数字：answer 是精确数值，数值相等就算对（3/4 和 0.75 都行）
 *  - 填单词：words 是所有可接受的单词（不分大小写）
 *  - 四选一：options 是打乱顺序后的四个选项，words 里是正确选项
 * source 是题目出处（哪所学校哪年的考卷），自编的题为空。
 */
data class Question(
    val topic: String,
    val text: String,
    val answerText: String,
    val explain: String,
    val answer: Frac? = null,
    val words: List<String> = emptyList(),
    val options: List<String> = emptyList(),
    val level: String = "Primary 5",
    val subject: Subject = when {
        answer != null -> Subject.MATH
        options.isNotEmpty() -> Subject.CHINESE
        else -> Subject.ENGLISH
    },
    val source: String = "",
) {
    val kind: Kind
        get() = when {
            answer != null -> Kind.NUMBER
            options.isNotEmpty() -> Kind.CHOICE
            else -> Kind.WORD
        }
    val isEnglish get() = kind == Kind.WORD
    val isChoice get() = kind == Kind.CHOICE

    /** 华文题的界面提示和讲解用中文，其他科目用英文 */
    val isChineseUi get() = subject == Subject.CHINESE

    fun isCorrect(input: String): Boolean =
        if (answer != null) Frac.parse(input) == answer
        else normalize(input).let { s -> s.isNotEmpty() && words.any { normalize(it) == s } }

    companion object {
        fun normalize(s: String) = s.trim().lowercase().replace('’', '\'').replace('‘', '\'')
    }
}

/** 从 APK 的 assets/<dir>/ 读出全部 txt 题库文件的所有行（文件按名字排序） */
internal fun readAssetDir(ctx: android.content.Context, dir: String): List<String> {
    val am = ctx.assets
    return am.list(dir).orEmpty().filter { it.endsWith(".txt") }.sorted()
        .flatMap { f -> am.open("$dir/$f").bufferedReader().readLines() }
}

/**
 * 英文填空题库（P5 在 assets/english，Sec 2 在 assets/english_sec2），一行一题：
 *   题型|句子（___ 是空格）|答案（多个用 / 分隔）|解释
 * 介词、连词、代词、数量词、短语动词、词汇题（P C R N X V）会显示首字母和字母数，避免一题多解。
 */
class FillBank(val level: String, val dir: String) {
    private val topics = mapOf(
        "T" to "Verb forms", "S" to "Subject-verb agreement", "W" to "Word forms", "U" to "Plurals",
        "A" to "Comparatives", "P" to "Prepositions", "C" to "Connectors", "R" to "Pronouns",
        "N" to "Quantifiers & group words", "Q" to "Question tags", "X" to "Phrasal verbs", "V" to "Vocabulary",
    )
    private val HINT_TOPICS = "PCRNXV"

    data class Item(val code: String, val sentence: String, val answers: List<String>, val explain: String)

    @Volatile
    var items: List<Item> = emptyList()
        private set

    private var deck = ArrayDeque<Item>()

    /** 解析题库文本；格式不对的行会抛异常（单元测试会检查整个题库） */
    fun parse(lines: List<String>): List<Item> = lines.map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { line ->
            val p = line.split('|')
            require(p.size == 4) { "需要 4 段：$line" }
            val (code, sentence, ans, explain) = p
            require(code in topics) { "未知题型 $code：$line" }
            require(Regex("___").findAll(sentence).count() == 1) { "要有且只有一个 ___：$line" }
            val answers = ans.split('/').map { it.trim() }.filter { it.isNotEmpty() }
            require(answers.isNotEmpty()) { "没有答案：$line" }
            Item(code, sentence, answers, explain)
        }

    fun load(lines: List<String>) {
        items = parse(lines)
        deck = ArrayDeque()
    }

    /** 从 APK 的 assets 读取题库（只读一次） */
    fun loadAssets(ctx: android.content.Context) {
        if (items.isEmpty()) load(readAssetDir(ctx, dir))
    }

    /** 把空格画出来：需要提示的题显示首字母和字母数，例如 o _ _ */
    fun blank(item: Item): String {
        val a = item.answers.first()
        return if (item.code in HINT_TOPICS) "${a.first()}" + " _".repeat(a.length - 1) else "________"
    }

    fun toQuestion(item: Item): Question {
        val hint = if (item.code in HINT_TOPICS)
            "\n\n(The first letter is given. The word has ${item.answers.first().length} letters.)" else ""
        return Question(
            topic = topics.getValue(item.code),
            text = item.sentence.replace("___", blank(item)) + hint,
            answerText = "\"${item.answers.first()}\"",
            explain = item.explain,
            words = item.answers,
            level = level,
        )
    }

    /** 洗牌后依次出题，整个题库做完一轮才会重复 */
    fun next(r: Random): Question? {
        if (items.isEmpty()) return null
        if (deck.isEmpty()) deck = ArrayDeque(items.shuffled(r))
        return toQuestion(deck.removeFirst())
    }
}

/**
 * 华文选择题库（P5 在 assets/chinese_p5，Sec 2 在 assets/chinese_sec2），一行一题：
 *   题型代码|题干|正确答案|干扰项1/干扰项2/干扰项3|解释
 * 电视遥控器没法输入汉字，所以华文题是四选一，选项每次随机打乱。
 */
class ChoiceBank(val level: String, val dir: String) {
    private val topics = mapOf(
        "Z" to "字词", "H" to "汉语拼音", "L" to "量词", "F" to "近义词·反义词", "C" to "选词填空",
        "Y" to "成语", "G" to "关联词", "J" to "句子", "D" to "阅读理解", "W" to "文言与古诗文",
    )

    data class Item(val code: String, val stem: String, val answer: String, val wrong: List<String>, val explain: String)

    @Volatile
    var items: List<Item> = emptyList()
        private set

    private var deck = ArrayDeque<Item>()

    /** 解析题库文本；格式不对的行会抛异常（单元测试会检查整个题库） */
    fun parse(lines: List<String>): List<Item> = lines.map { it.trim().removePrefix("﻿") }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { line ->
            val p = line.split('|').map { it.trim() }
            require(p.size == 5) { "需要 5 段：$line" }
            val (code, stem, answer, wrongs, explain) = p
            require(code in topics) { "未知题型 $code：$line" }
            val wrong = wrongs.split('/').map { it.trim() }
            require(wrong.size == 3) { "要有 3 个干扰项：$line" }
            val all = wrong + answer
            require(all.all { it.isNotEmpty() } && all.toSet().size == 4) { "选项不能为空或重复：$line" }
            require(stem.isNotEmpty() && explain.isNotEmpty()) { "题干和解释不能为空：$line" }
            Item(code, stem, answer, wrong, explain)
        }

    fun load(lines: List<String>) {
        items = parse(lines)
        deck = ArrayDeque()
    }

    fun loadAssets(ctx: android.content.Context) {
        if (items.isEmpty()) load(readAssetDir(ctx, dir))
    }

    fun toQuestion(item: Item, r: Random): Question = Question(
        topic = topics.getValue(item.code),
        text = item.stem,
        answerText = "「${item.answer}」",
        explain = item.explain,
        words = listOf(item.answer),
        options = (item.wrong + item.answer).shuffled(r),
        level = level,
    )

    fun next(r: Random): Question? {
        if (items.isEmpty()) return null
        if (deck.isEmpty()) deck = ArrayDeque(items.shuffled(r))
        return toQuestion(deck.removeFirst(), r)
    }
}

/**
 * 真题题库：题目取自新加坡各校的考卷（一份卷子一个 txt 文件），一行一题，7 段：
 *   作答方式|题型|题目|答案|干扰项|讲解|出处
 *  - 作答方式：N = 填数字（答案是整数/小数/分数，可为负），W = 填一个英文单词（多个可接受答案用 / 分隔），
 *              C = 四选一（干扰项 3 个，用 ~ 分隔）；N 和 W 的干扰项留空
 *  - 题目和讲解里的 \n 表示换行
 */
class PaperBank(val level: String, val subject: Subject, val dir: String) {

    data class Item(
        val kind: Kind, val topic: String, val text: String, val answers: List<String>,
        val wrong: List<String>, val explain: String, val source: String,
    )

    @Volatile
    var items: List<Item> = emptyList()
        private set

    private var deck = ArrayDeque<Item>()

    /** 解析题库文本；格式不对的行会抛异常（单元测试会检查整个题库） */
    fun parse(lines: List<String>): List<Item> = lines.map { it.trim().removePrefix("﻿") }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { line ->
            val p = line.split('|').map { it.trim() }
            require(p.size == 7) { "需要 7 段（现在 ${p.size} 段）：$line" }
            val kind = when (p[0]) {
                "N" -> Kind.NUMBER
                "W" -> Kind.WORD
                "C" -> Kind.CHOICE
                else -> throw IllegalArgumentException("作答方式只能是 N/W/C：$line")
            }
            val text = p[2].replace("\\n", "\n")
            val explain = p[5].replace("\\n", "\n")
            require(p[1].isNotEmpty() && text.isNotEmpty() && explain.isNotEmpty() && p[6].isNotEmpty()) {
                "题型、题目、讲解、出处都不能为空：$line"
            }
            val answers: List<String>
            var wrong = emptyList<String>()
            when (kind) {
                Kind.NUMBER -> {
                    require(Frac.parse(p[3]) != null) { "答案不是数字：$line" }
                    require(p[4].isEmpty()) { "填数字题不要干扰项：$line" }
                    answers = listOf(p[3])
                }
                Kind.WORD -> {
                    answers = p[3].split('/').map { it.trim() }.filter { it.isNotEmpty() }
                    require(answers.isNotEmpty() && answers.all { Regex("[A-Za-z']+").matches(it) }) {
                        "填单词题的答案只能是一个英文单词：$line"
                    }
                    require(p[4].isEmpty()) { "填单词题不要干扰项：$line" }
                }
                Kind.CHOICE -> {
                    answers = listOf(p[3])
                    wrong = p[4].split('~').map { it.trim() }
                    require(wrong.size == 3) { "要有 3 个干扰项（用 ~ 分隔）：$line" }
                    val all = wrong + p[3]
                    require(all.all { it.isNotEmpty() } && all.toSet().size == 4) { "选项不能为空或重复：$line" }
                }
            }
            Item(kind, p[1], text, answers, wrong, explain, p[6])
        }

    fun load(lines: List<String>) {
        items = parse(lines)
        deck = ArrayDeque()
    }

    fun loadAssets(ctx: android.content.Context) {
        if (items.isEmpty()) load(readAssetDir(ctx, dir))
    }

    fun toQuestion(item: Item, r: Random): Question = when (item.kind) {
        Kind.NUMBER -> Question(
            item.topic, item.text, item.answers.first(), item.explain,
            answer = Frac.parse(item.answers.first()), level = level, subject = subject, source = item.source,
        )
        Kind.WORD -> Question(
            item.topic, item.text, "\"${item.answers.first()}\"", item.explain,
            words = item.answers, level = level, subject = subject, source = item.source,
        )
        Kind.CHOICE -> Question(
            item.topic, item.text,
            if (subject == Subject.CHINESE) "「${item.answers.first()}」" else "\"${item.answers.first()}\"",
            item.explain, words = item.answers, options = (item.wrong + item.answers.first()).shuffled(r),
            level = level, subject = subject, source = item.source,
        )
    }

    /** 洗牌后依次出题，整个题库做完一轮才会重复 */
    fun next(r: Random): Question? {
        if (items.isEmpty()) return null
        if (deck.isEmpty()) deck = ArrayDeque(items.shuffled(r))
        return toQuestion(deck.removeFirst(), r)
    }
}

/**
 * 题库。现在出的题全部来自新加坡各校真题（assets/p5_* 和 assets/sec2_*）。
 * 以前自编的题库（英文填空、华文选择）已搁置，文件移到了仓库的 old_banks/ 目录，不再打包进 APK；
 * FillBank / ChoiceBank 和随机数学题生成器保留在代码里，以后需要可以再接回来。
 */
object Banks {
    val englishP5 = PaperBank("Primary 5", Subject.ENGLISH, "p5_english")
    val mathP5 = PaperBank("Primary 5", Subject.MATH, "p5_maths")
    val chineseP5 = PaperBank("Primary 5", Subject.CHINESE, "p5_chinese")
    val englishSec2 = PaperBank("Secondary 2", Subject.ENGLISH, "sec2_english")
    val mathSec2 = PaperBank("Secondary 2", Subject.MATH, "sec2_maths")
    val scienceSec2 = PaperBank("Secondary 2", Subject.SCIENCE, "sec2_science")

    val all = listOf(englishP5, mathP5, chineseP5, englishSec2, mathSec2, scienceSec2)

    /** 读取全部题库；某个读不到就跳过（出题时会换别的科目） */
    fun loadAll(ctx: android.content.Context) {
        for (b in all) runCatching { b.loadAssets(ctx) }
    }
}

/**
 * 新加坡小学五年级（Primary 5）英文数学应用题：参数随机，答案保证精确、整洁，
 * 每题都有分步解答。题型参考 P5 课纲：分数（含剩余部分）、比、百分数、平均数、
 * 速率、体积与容量、三角形面积、小数与钱、以及常见解题法（单位法、假设法、盈亏、年龄）。
 */
object Quiz {

    private val r = Random.Default

    private fun rnd(from: Int, to: Int) = r.nextInt(from, to + 1)
    private fun <T> pick(vararg xs: T): T = xs[r.nextInt(xs.size)]
    private fun coprime(a: Int, b: Int) = Frac.gcd(a.toLong(), b.toLong()) == 1L
    private fun f(n: Int, d: Int) = Frac.of(n, d)
    private fun n(v: Int) = Frac.of(v)
    private fun m(v: Int) = "$$v"
    private fun m(v: Frac) = v.toMoney()

    /** 分子：1..d-1 中和 d 互质的数，保证分数是最简 */
    private fun numFor(d: Int): Int {
        var x: Int
        do { x = rnd(1, d - 1) } while (!coprime(x, d))
        return x
    }

    private class Kid(val name: String, val he: String, val his: String) {
        val heCap get() = he.replaceFirstChar { it.uppercase() }
    }

    private val kids = listOf(
        Kid("Ali", "he", "his"), Kid("Ben", "he", "his"), Kid("Wei Jie", "he", "his"),
        Kid("Kumar", "he", "his"), Kid("Farid", "he", "his"), Kid("Ryan", "he", "his"),
        Kid("Siti", "she", "her"), Kid("Mei Ling", "she", "her"), Kid("Devi", "she", "her"),
        Kid("Hui Min", "she", "her"), Kid("Emily", "she", "her"), Kid("Nurul", "she", "her"),
    )

    private fun kid() = kids[r.nextInt(kids.size)]

    private fun kids(count: Int): List<Kid> = kids.shuffled(r).take(count)

    private val generators: List<() -> Question> = listOf(
        ::fractionRemainder, ::fractionRemainderReverse, ::fractionOfFraction,
        ::ratioChange, ::ratioShare, ::ratioDifference,
        ::discount, ::percentGroup,
        ::averageNewMember, ::averageTarget,
        ::tankFill, ::waterRise, ::cubesInBox,
        ::shopping, ::excessShortage, ::busCost,
        ::ageProblem, ::chickensRabbits, ::boxMass,
        ::ribbon, ::triangleCut, ::printers,
    )

    val generatorCount get() = generators.size

    private var lastIndex = -1

    /** 一个出题来源和它的权重 */
    class Source(val name: String, val weight: Int, val next: () -> Question?)

    /**
     * 出题比例（权重合计 16），全部来自真题题库：
     *  - P5 : Sec 2 = 3 : 1（12 : 4）
     *  - P5 英文 : 数学 : 华文 = 2 : 1 : 1
     *  - Sec 2 英文 : 数学 : 科学 = 2 : 1 : 1
     */
    val sources = listOf(
        Source("P5 English", 6) { Banks.englishP5.next(r) },
        Source("P5 Maths", 3) { Banks.mathP5.next(r) },
        Source("P5 华文", 3) { Banks.chineseP5.next(r) },
        Source("Sec 2 English", 2) { Banks.englishSec2.next(r) },
        Source("Sec 2 Maths", 1) { Banks.mathSec2.next(r) },
        Source("Sec 2 Science", 1) { Banks.scienceSec2.next(r) },
    )

    /** 按权重随机选一个来源出题；某个题库没加载（返回 null）就重新选，实在不行出 P5 数学 */
    fun next(): Question {
        repeat(10) {
            var x = r.nextInt(sources.sumOf { it.weight })
            val s = sources.first { x -= it.weight; x < 0 }
            s.next()?.let { return it }
        }
        return nextMath()
    }

    /** 下一道数学题（不和上一题同一类型） */
    fun nextMath(): Question {
        var i: Int
        do { i = r.nextInt(generators.size) } while (i == lastIndex)
        lastIndex = i
        return generators[i]()
    }

    private fun q(topic: String, text: String, ans: Frac, ansText: String, vararg steps: String) =
        Question(topic, text, ansText, steps.joinToString("\n"), answer = ans)

    // ---------------- Fractions ----------------

    private fun fractionRemainder(): Question {
        val k = kid()
        val a = pick(3, 4, 5); val n1 = numFor(a)
        val b = pick(2, 3, 4, 5); val n2 = numFor(b)
        val total = a * b * rnd(2, 12) * pick(1, 2, 5)
        val bag = total / a * n1
        val rem = total - bag
        val book = rem / b * n2
        val left = rem - book
        return q("Fractions",
            "${k.name} had ${m(total)}. ${k.heCap} spent $n1/$a of ${k.his} money on a bag and " +
                "$n2/$b of the remaining money on a book.\nHow much money did ${k.he} have left?",
            n(left), m(left),
            "Bag: $n1/$a × ${m(total)} = ${m(bag)}",
            "Remaining: ${m(total)} − ${m(bag)} = ${m(rem)}",
            "Book: $n2/$b × ${m(rem)} = ${m(book)}",
            "Left: ${m(rem)} − ${m(book)} = ${m(left)}")
    }

    private fun fractionRemainderReverse(): Question {
        val k = kid()
        val a = pick(3, 4, 5); val n1 = numFor(a)
        val b = pick(2, 3, 4, 5); val n2 = numFor(b)
        val total = a * b * rnd(2, 12) * pick(1, 2, 5)
        val leftFrac = f(a - n1, a) * f(b - n2, b)
        val left = (leftFrac * total).num.toInt()
        return q("Fractions",
            "${k.name} spent $n1/$a of ${k.his} money on a pair of shoes and $n2/$b of the remaining " +
                "money on a shirt. ${k.heCap} had ${m(left)} left.\nHow much money did ${k.he} have at first?",
            n(total), m(total),
            "After buying the shoes, ${a - n1}/$a of the money was left.",
            "After buying the shirt, ${b - n2}/$b of that was left.",
            "${b - n2}/$b × ${a - n1}/$a = ${leftFrac.toFracString()} of the money at first = ${m(left)}",
            "Money at first: ${m(left)} ÷ ${leftFrac.toFracString()} = ${m(total)}")
    }

    private fun fractionOfFraction(): Question {
        val d1 = pick(3, 4, 5, 7, 8); val n1 = numFor(d1)
        val d2 = pick(2, 3, 4, 5); val n2 = numFor(d2)
        val total = d1 * d2 * rnd(2, 8)
        val girls = total / d1 * n1
        val spec = girls / d2 * n2
        val boys = total - girls
        val fr = f(n2, d2) * f(n1, d1)
        return q("Fractions",
            "In a school hall, $n1/$d1 of the pupils are girls. $n2/$d2 of the girls wear spectacles. " +
                "$spec girls wear spectacles.\nHow many boys are there in the hall?",
            n(boys), "$boys",
            "Girls with spectacles: $n2/$d2 × $n1/$d1 = ${fr.toFracString()} of all the pupils",
            "${fr.toFracString()} of the pupils = $spec, so all the pupils = $spec ÷ ${fr.toFracString()} = $total",
            "Girls: $n1/$d1 × $total = $girls",
            "Boys: $total − $girls = $boys")
    }

    private fun ribbon(): Question {
        val k = kid()
        val p = pick(f(1, 2), f(3, 4), f(5, 4), f(3, 2))
        val cnt = rnd(2, 5)
        val left = f(rnd(2, 12), 4)
        val used = p * cnt
        val total = used + left
        val pieces = (left * 4).num.toInt()
        return q("Fractions",
            "${k.name} had a ribbon ${total.toMixedString()} m long. ${k.heCap} cut $cnt pieces, each " +
                "${p.toMixedString()} m long. Then ${k.he} cut the remaining ribbon into pieces of 1/4 m each.\n" +
                "How many 1/4-m pieces did ${k.he} get?",
            n(pieces), "$pieces",
            "Ribbon used: $cnt × ${p.toMixedString()} m = ${used.toMixedString()} m",
            "Ribbon left: ${total.toMixedString()} m − ${used.toMixedString()} m = ${left.toMixedString()} m",
            "Number of 1/4-m pieces: ${left.toMixedString()} ÷ 1/4 = ${left.toMixedString()} × 4 = $pieces")
    }

    // ---------------- Ratio ----------------

    private fun ratioChange(): Question {
        var a: Int; var b: Int; var c: Int
        do {
            a = rnd(3, 9); b = rnd(2, 9); c = rnd(2, a - 1)
        } while (a == b || !coprime(a, b) || !coprime(c, b))
        val u = rnd(3, 15)
        val leftCount = (a - c) * u
        val girls = b * u
        return q("Ratio",
            "The ratio of the number of boys to the number of girls in a hall was $a : $b. " +
                "After $leftCount boys left the hall, the ratio became $c : $b.\nHow many girls were in the hall?",
            n(girls), "$girls",
            "The number of girls did not change, so the girls stay as $b units.",
            "Boys: $a units → $c units, so ${a - c} units = $leftCount",
            "1 unit = $leftCount ÷ ${a - c} = $u",
            "Girls: $b × $u = $girls")
    }

    private fun ratioShare(): Question {
        val (x, y, z) = kids(3)
        var a: Int; var b: Int; var c: Int
        do {
            a = rnd(1, 6); b = rnd(1, 7); c = rnd(2, 9)
        } while (c <= a || Frac.gcd(Frac.gcd(a.toLong(), b.toLong()), c.toLong()) != 1L)
        val u = rnd(4, 30) * pick(1, 5)
        val s = a + b + c
        val total = s * u
        val ans = (c - a) * u
        return q("Ratio",
            "${x.name}, ${y.name} and ${z.name} shared ${m(total)} in the ratio $a : $b : $c.\n" +
                "How much more money did ${z.name} receive than ${x.name}?",
            n(ans), m(ans),
            "Total units: $a + $b + $c = $s",
            "1 unit = ${m(total)} ÷ $s = ${m(u)}",
            "${z.name} received $c − $a = ${c - a} units more than ${x.name}.",
            "${c - a} × ${m(u)} = ${m(ans)}")
    }

    private fun ratioDifference(): Question {
        val (x, y) = kids(2)
        var a: Int; var b: Int
        do { a = rnd(1, 7); b = rnd(2, 9) } while (b <= a || !coprime(a, b))
        val u = rnd(5, 40) * pick(1, 2, 5)
        val d = (b - a) * u
        val ans = (a + b) * u
        return q("Ratio",
            "The ratio of ${x.name}'s savings to ${y.name}'s savings is $a : $b. " +
                "${y.name} has ${m(d)} more than ${x.name}.\nHow much money do they have altogether?",
            n(ans), m(ans),
            "Difference: $b − $a = ${b - a} units = ${m(d)}",
            "1 unit = ${m(d)} ÷ ${b - a} = ${m(u)}",
            "Altogether: $a + $b = ${a + b} units",
            "${a + b} × ${m(u)} = ${m(ans)}")
    }

    // ---------------- Percentage ----------------

    private fun discount(): Question {
        val price = rnd(4, 40) * 5
        val p = pick(10, 15, 20, 25, 30, 40)
        val cnt = rnd(2, 5)
        val disc = n(price) * f(p, 100)
        val sale = n(price) - disc
        val total = sale * cnt
        return q("Percentage",
            "A shirt cost ${m(price)}. During a sale, it was sold at a discount of $p%. " +
                "Mrs Tan bought $cnt shirts during the sale.\nHow much did she pay?",
            total, m(total),
            "Discount: $p% × ${m(price)} = ${m(disc)}",
            "Sale price of 1 shirt: ${m(price)} − ${m(disc)} = ${m(sale)}",
            "$cnt shirts: $cnt × ${m(sale)} = ${m(total)}")
    }

    private fun percentGroup(): Question {
        var total: Int; var p: Int; var qp: Int
        do {
            total = rnd(5, 40) * 20
            p = pick(40, 45, 55, 60, 65, 75)
            qp = pick(20, 25, 40, 50, 60, 75)
        } while (total * p % 100 != 0 || total * p / 100 * qp % 100 != 0)
        val boys = total * p / 100
        val glasses = boys * qp / 100
        val ans = boys - glasses
        return q("Percentage",
            "There are $total pupils in a school hall. $p% of them are boys. $qp% of the boys wear spectacles.\n" +
                "How many boys do not wear spectacles?",
            n(ans), "$ans",
            "Boys: $p% × $total = $boys",
            "Boys with spectacles: $qp% × $boys = $glasses",
            "Boys without spectacles: $boys − $glasses = $ans")
    }

    // ---------------- Average ----------------

    private fun averageNewMember(): Question {
        val k = kid()
        var cnt: Int; var a: Int; var b: Int; var x: Int
        do {
            cnt = rnd(3, 5); a = rnd(30, 45); b = a + pick(-2, -1, 1, 2, 3)
            x = (cnt + 1) * b - cnt * a
        } while (x !in 28..60)
        return q("Average",
            "The average mass of $cnt children is $a kg. When ${k.name} joined the group, " +
                "the average mass became $b kg.\nWhat is ${k.name}'s mass in kg?",
            n(x), "$x kg",
            "Total mass of $cnt children: $cnt × $a = ${cnt * a} kg",
            "Total mass of ${cnt + 1} children: ${cnt + 1} × $b = ${(cnt + 1) * b} kg",
            "${k.name}'s mass: ${(cnt + 1) * b} − ${cnt * a} = $x kg")
    }

    private fun averageTarget(): Question {
        val k = kid()
        var t: Int; var a: Int; var b: Int; var need: Int
        do {
            t = rnd(3, 4); a = rnd(68, 88); b = a + rnd(1, 4)
            need = (t + 1) * b - t * a
        } while (need > 100)
        return q("Average",
            "${k.name}'s average score for $t tests was $a marks. ${k.heCap} wants ${k.his} average score " +
                "for ${t + 1} tests to be $b marks.\nWhat score must ${k.he} get in the next test?",
            n(need), "$need",
            "Total for $t tests: $t × $a = ${t * a}",
            "Total needed for ${t + 1} tests: ${t + 1} × $b = ${(t + 1) * b}",
            "Score needed: ${(t + 1) * b} − ${t * a} = $need")
    }

    // ---------------- Volume ----------------

    private fun tankFill(): Question {
        while (true) {
            val l = pick(40, 50, 60, 80, 100)
            val w = pick(20, 25, 30, 40, 50)
            val h = pick(20, 30, 40, 50, 60)
            val fill = pick(f(0, 1), f(1, 4), f(1, 2), f(2, 5))
            val v = l * w * h
            val need = (n(v) * (n(1) - fill))
            if (!need.isInt || need.num % 1000 != 0L) continue
            val litres = (need.num / 1000).toInt()
            val rate = listOf(2, 3, 4, 5, 6, 8, 10, 12, 15, 20)
                .filter { litres % it == 0 && litres / it in 3..60 }
                .randomOrNull(r) ?: continue
            val t = litres / rate
            val state = if (fill.num == 0L) "It is empty." else "It is ${fill.toFracString()} filled with water."
            val steps = mutableListOf("Volume of tank: $l × $w × $h = $v cm³")
            if (fill.num != 0L) steps += "Water needed: (1 − ${fill.toFracString()}) × $v = ${need.num} cm³"
            steps += "${need.num} cm³ = $litres ℓ"
            steps += "Time: $litres ÷ $rate = $t minutes"
            return q("Volume",
                "A rectangular tank measures $l cm by $w cm by $h cm. $state Water flows from a tap into " +
                    "the tank at $rate litres per minute.\nHow many minutes will it take to fill the tank " +
                    "completely? (1 ℓ = 1000 cm³)",
                n(t), "$t minutes", *steps.toTypedArray())
        }
    }

    private fun waterRise(): Question {
        val l = pick(20, 25, 30, 40, 50, 60)
        val w = pick(20, 25, 30, 40, 50)
        val rise = Frac.dec(rnd(4, 30) * 5, 1) // 2.0 – 15.0 cm
        val start = rnd(5, 15)
        val cm3 = rise * (l * w)
        val litres = cm3 / 1000
        val ans = rise + start
        return q("Volume",
            "A rectangular tank has a base of $l cm by $w cm. It contains water to a height of $start cm. " +
                "$litres litres of water are poured into the tank.\n" +
                "What is the new height of the water level in cm? (1 ℓ = 1000 cm³)",
            ans, "$ans cm",
            "$litres ℓ = $cm3 cm³",
            "Rise in water level: $cm3 ÷ ($l × $w) = $cm3 ÷ ${l * w} = $rise cm",
            "New height: $start + $rise = $ans cm")
    }

    private fun cubesInBox(): Question {
        val k = kid()
        val s = pick(2, 3, 4, 5)
        val x = rnd(2, 8); val y = rnd(2, 6); val z = rnd(2, 5)
        val cnt = x * y * z
        val g = pick(20, 25, 40, 50, 60, 75, 80, 120)
        val totalG = cnt * g
        val kg = f(totalG, 1000)
        return q("Volume",
            "A box measures ${x * s} cm by ${y * s} cm by ${z * s} cm. ${k.name} packed as many $s-cm cubes " +
                "as possible into the box. Each cube has a mass of $g g.\nWhat is the total mass of the cubes in kg?",
            kg, "$kg kg",
            "Cubes along each side: ${x * s} ÷ $s = $x, ${y * s} ÷ $s = $y, ${z * s} ÷ $s = $z",
            "Number of cubes: $x × $y × $z = $cnt",
            "Total mass: $cnt × $g g = $totalG g = $kg kg")
    }

    // ---------------- Area ----------------

    private fun triangleCut(): Question {
        val l = rnd(12, 30); val w = rnd(8, 20)
        val b = rnd(4, l)
        val tri = f(b * w, 2)
        val ans = n(l * w) - tri
        return q("Area of triangle",
            "A rectangular piece of paper measures $l cm by $w cm. A triangle with a base of $b cm and a " +
                "height of $w cm is cut out from it.\nWhat is the area of the paper left in cm²?",
            ans, "$ans cm²",
            "Area of rectangle: $l × $w = ${l * w} cm²",
            "Area of triangle: 1/2 × $b × $w = $tri cm²",
            "Area left: ${l * w} − $tri = $ans cm²")
    }

    // ---------------- Decimals & money ----------------

    private fun shopping(): Question {
        while (true) {
            val q1 = rnd(2, 4)
            val x = Frac.dec(rnd(150, 350), 1)
            val q2 = Frac.dec(pick(15, 25, 35), 1)
            val y = Frac.dec(rnd(60, 160), 1)
            val c1 = x * q1
            val c2 = y * q2
            val total = c1 + c2
            if (total < n(40) || total >= n(100)) continue
            val change = n(100) - total
            return q("Decimals",
                "Mrs Lim bought $q1 kg of prawns at ${m(x)} per kg and $q2 kg of fish at ${m(y)} per kg. " +
                    "She paid with a \$100 note.\nHow much change did she receive?",
                change, m(change),
                "Prawns: $q1 × ${m(x)} = ${m(c1)}",
                "Fish: $q2 × ${m(y)} = ${m(c2)}",
                "Total: ${m(c1)} + ${m(c2)} = ${m(total)}",
                "Change: \$100 − ${m(total)} = ${m(change)}")
        }
    }

    private fun excessShortage(): Question {
        val k = kid()
        val pc = rnd(12, 60) * 5          // 每支笔多少分
        val n1 = rnd(4, 10)
        val d = rnd(2, 5)
        val n2 = n1 + d
        val leftC = rnd(1, d * pc / 5 - 1) * 5
        val shortC = d * pc - leftC
        val p = f(pc, 100); val left = f(leftC, 100); val short = f(shortC, 100)
        val money = p * n1 + left
        return q("Money",
            "If ${k.name} buys $n1 pens, ${k.he} will have ${m(left)} left. If ${k.he} buys $n2 pens, " +
                "${k.he} will need ${m(short)} more.\nHow much money does ${k.name} have?",
            money, m(money),
            "The extra $d pens cost ${m(left)} + ${m(short)} = ${m(left + short)}",
            "1 pen costs ${m(left + short)} ÷ $d = ${m(p)}",
            "Money: $n1 × ${m(p)} + ${m(left)} = ${m(money)}")
    }

    private fun busCost(): Question {
        var cap: Int; var pupils: Int; var teachers: Int
        do {
            cap = pick(40, 44, 45, 48)
            pupils = rnd(150, 400); teachers = rnd(8, 20)
        } while ((pupils + teachers) % cap == 0)
        val people = pupils + teachers
        val buses = people / cap + 1
        val cost = rnd(18, 40) * 10
        val ans = buses * cost
        return q("Whole numbers",
            "$pupils pupils and $teachers teachers are going on a school trip. Each bus can carry at most " +
                "$cap people. It costs ${m(cost)} to hire one bus.\nWhat is the least amount of money needed to hire the buses?",
            n(ans), m(ans),
            "Number of people: $pupils + $teachers = $people",
            "$people ÷ $cap = ${people / cap} remainder ${people % cap}, so $buses buses are needed",
            "Cost: $buses × ${m(cost)} = ${m(ans)}")
    }

    private fun boxMass(): Question {
        val e = f(rnd(5, 16) * 50, 1000)       // 0.25 – 0.8 kg
        val cnt = rnd(6, 15)
        val g = rnd(6, 24) * 25                 // 150 – 600 g
        val booksKg = f(cnt * g, 1000)
        val total = e + booksKg
        return q("Decimals",
            "An empty box has a mass of $e kg. When $cnt identical books are put into it, the total mass " +
                "becomes $total kg.\nWhat is the mass of one book in grams?",
            n(g), "$g g",
            "Mass of $cnt books: $total − $e = $booksKg kg",
            "$booksKg kg = ${cnt * g} g",
            "1 book: ${cnt * g} ÷ $cnt = $g g")
    }

    // ---------------- Rate ----------------

    private fun printers(): Question {
        while (true) {
            val r1 = pick(12, 15, 18, 20, 24, 25, 30)
            val r2 = pick(10, 15, 20, 24, 30, 36, 40)
            val pages = rnd(4, 40) * 5
            val copies = rnd(3, 12)
            val total = pages * copies
            val both = r1 + r2
            if (total % both != 0 || total / both !in 4..90) continue
            val t = total / both
            return q("Rate",
                "Printer A prints $r1 pages per minute. Printer B prints $r2 pages per minute. Both printers " +
                    "work together to print $copies copies of a $pages-page report.\nHow many minutes will they take?",
                n(t), "$t minutes",
                "Pages to print: $copies × $pages = $total",
                "Together in 1 minute: $r1 + $r2 = $both pages",
                "Time: $total ÷ $both = $t minutes")
        }
    }

    // ---------------- Problem-solving heuristics ----------------

    private fun ageProblem(): Question {
        var s: Int; var k: Int
        do { s = rnd(5, 12); k = rnd(3, 5) } while (k * s > 50)
        val y = rnd(2, 10)
        val sum = (k + 1) * s + 2 * y
        return q("Age problem",
            "Mr Tan is $k times as old as his son now. In $y years' time, the sum of their ages will be $sum.\n" +
                "How old is Mr Tan now?",
            n(k * s), "${k * s}",
            "Sum of their ages now: $sum − $y − $y = ${sum - 2 * y}",
            "Son = 1 unit, Mr Tan = $k units, total = ${k + 1} units",
            "1 unit = ${sum - 2 * y} ÷ ${k + 1} = $s",
            "Mr Tan: $k × $s = ${k * s}")
    }

    private fun chickensRabbits(): Question {
        val total = rnd(15, 50)
        val rabbits = rnd(3, total - 3)
        val legs = 2 * total + 2 * rabbits
        return q("Supposition",
            "A farmer has $total chickens and rabbits altogether. They have $legs legs in total.\n" +
                "How many rabbits are there?",
            n(rabbits), "$rabbits",
            "Suppose all $total animals were chickens: $total × 2 = ${2 * total} legs",
            "Extra legs: $legs − ${2 * total} = ${2 * rabbits}",
            "Each rabbit has 2 more legs than a chicken.",
            "Rabbits: ${2 * rabbits} ÷ 2 = $rabbits")
    }
}
