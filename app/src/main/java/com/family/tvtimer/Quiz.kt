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

/**
 * 一道题。answer 用来判对错（数值相等就算对，3/4 和 0.75 都行），
 * answerText 是显示给小孩看的标准答案，explain 是答错后显示的解题步骤。
 */
data class Question(
    val topic: String,
    val text: String,
    val answer: Frac,
    val answerText: String,
    val explain: String,
)

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

    /** 下一道题（不和上一题同一类型） */
    fun next(): Question {
        var i: Int
        do { i = r.nextInt(generators.size) } while (i == lastIndex)
        lastIndex = i
        return generators[i]()
    }

    private fun q(topic: String, text: String, ans: Frac, ansText: String, vararg steps: String) =
        Question(topic, text, ans, ansText, steps.joinToString("\n"))

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
