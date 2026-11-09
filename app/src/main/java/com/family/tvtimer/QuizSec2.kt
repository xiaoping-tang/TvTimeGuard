package com.family.tvtimer

import kotlin.random.Random

/**
 * 新加坡中二（Secondary 2 Express）英文数学题：参数随机，答案精确，每题有分步解答。
 * 答案可能是负数或分数：电视数字键盘有 - 和 / 键。
 * 题型参考 Sec 1–2 课纲：代数式、一元一次方程、联立方程、展开与因式分解、二次方程、
 * 勾股定理、直线斜率与截距、百分数变化、正比与反比、多边形内角、平均数与中位数、
 * 概率、圆柱体积、速率、不等式、单利。
 */
object MathSec2 {
    private const val LEVEL = "Secondary 2"
    private val r = Random.Default

    private fun rnd(from: Int, to: Int) = r.nextInt(from, to + 1)
    private fun <T> pick(vararg xs: T): T = xs[r.nextInt(xs.size)]
    private fun nz(from: Int, to: Int): Int {
        var v: Int
        do { v = rnd(from, to) } while (v == 0)
        return v
    }

    /** 题面里的负号用 −（更好看）；答案用普通数字（和键盘输入一致） */
    private fun s(v: Int) = if (v < 0) "−${-v}" else "$v"
    private fun s(v: Frac) = if (v.num < 0) "−${Frac.of(-v.num, v.den)}" else "$v"

    /** 把 a·x + b 写成 "3x − 5"、"−x + 2"、"x" 之类 */
    private fun lin(a: Int, b: Int, v: String = "x"): String {
        val ax = when (a) { 0 -> ""; 1 -> v; -1 -> "−$v"; else -> "${s(a)}$v" }
        if (b == 0) return ax.ifEmpty { "0" }
        if (ax.isEmpty()) return s(b)
        return if (b > 0) "$ax + $b" else "$ax − ${-b}"
    }

    /** 带括号的乘数：负数加括号 (−3) */
    private fun p(v: Int) = if (v < 0) "(${s(v)})" else "$v"

    private val generators: List<() -> Question> = listOf(
        ::linearBothSides, ::bracketEquation, ::fractionEquation, ::simultaneous,
        ::expandCoefficient, ::quadraticRoot, ::substitution, ::pythagoras,
        ::gradient, ::yIntercept, ::percentageChange, ::reversePercentage,
        ::proportion, ::polygon, ::median, ::probability, ::cylinder,
        ::averageSpeed, ::inequality, ::simpleInterest,
    )

    val generatorCount get() = generators.size

    private var lastIndex = -1

    fun next(): Question {
        var i: Int
        do { i = r.nextInt(generators.size) } while (i == lastIndex)
        lastIndex = i
        return generators[i]()
    }

    private fun q(topic: String, text: String, ans: Frac, vararg steps: String) =
        Question(topic, text, ans.toString(), steps.joinToString("\n"), answer = ans, level = LEVEL)

    // ---------------- Algebra ----------------

    private fun linearBothSides(): Question {
        while (true) {
            val x = nz(-9, 12)
            val a = rnd(2, 9); val c = rnd(1, 8)
            if (a == c) continue
            val b = nz(-20, 20)
            val d = a * x + b - c * x
            return q("Linear equations",
                "Solve the equation  ${lin(a, b)} = ${lin(c, d)}.\nx = ?",
                Frac.of(x),
                "${lin(a, 0)} − ${lin(c, 0)} = ${s(d)} − ${p(b)}",
                "${lin(a - c, 0)} = ${s(d - b)}",
                "x = ${s(d - b)} ÷ ${p(a - c)} = $x")
        }
    }

    private fun bracketEquation(): Question {
        val x = nz(-8, 12)
        val a = rnd(2, 6); val b = rnd(1, 9); val c = rnd(1, 5); val d = rnd(1, 9)
        if (a == c) return bracketEquation()
        val e = a * (x - b) - c * (x + d)
        return q("Linear equations",
            "Solve the equation  $a(x − $b) − $c(x + $d) = ${s(e)}.\nx = ?",
            Frac.of(x),
            "Expand: ${lin(a, -a * b)} − ${lin(c, c * d).let { "($it)" }} = ${s(e)}",
            "${lin(a - c, -a * b - c * d)} = ${s(e)}",
            "${lin(a - c, 0)} = ${s(e + a * b + c * d)}",
            "x = ${s(e + a * b + c * d)} ÷ ${p(a - c)} = $x")
    }

    private fun fractionEquation(): Question {
        val (a, b) = pick(2 to 3, 3 to 4, 2 to 5, 3 to 5, 4 to 6, 2 to 6, 4 to 5)
        val l = Frac.lcm(a.toLong(), b.toLong()).toInt()
        val x = l * nz(-4, 6)
        val c = Frac.of(x, a) + Frac.of(x, b)
        return q("Linear equations",
            "Solve the equation  x/$a + x/$b = ${s(c)}.\nx = ?",
            Frac.of(x),
            "Multiply both sides by $l: ${l / a}x + ${l / b}x = ${s(c * l)}",
            "${(l / a + l / b)}x = ${s(c * l)}",
            "x = ${s(c * l)} ÷ ${l / a + l / b} = $x")
    }

    private fun simultaneous(): Question {
        while (true) {
            val x = nz(-6, 9); val y = nz(-6, 9)
            val a = rnd(1, 5); val b = rnd(1, 5); val c = rnd(1, 5); val d = rnd(1, 5)
            if (a * d + b * c == 0) continue
            val e = a * x + b * y          // a x + b y = e
            val f = c * x - d * y          // c x − d y = f
            // 消去 y：(d)·第一式 + (b)·第二式
            val coef = a * d + b * c
            val rhs = d * e + b * f
            return q("Simultaneous equations",
                "Solve the simultaneous equations\n  ${a.let { if (it == 1) "" else "$it" }}x + ${b.let { if (it == 1) "" else "$it" }}y = ${s(e)}\n" +
                    "  ${c.let { if (it == 1) "" else "$it" }}x − ${d.let { if (it == 1) "" else "$it" }}y = ${s(f)}\nx = ?",
                Frac.of(x),
                "Multiply the 1st equation by $d and the 2nd by $b, then add to eliminate y:",
                "${coef}x = ${s(rhs)}",
                "x = ${s(rhs)} ÷ $coef = $x")
        }
    }

    private fun expandCoefficient(): Question {
        val a = rnd(1, 5); val b = nz(-9, 9); val c = rnd(1, 4); val d = nz(-9, 9)
        val coef = a * d + b * c
        if (coef == 0) return expandCoefficient()
        return q("Expansion",
            "Expand (${lin(a, b)})(${lin(c, d)}).\nWhat is the coefficient of x?",
            Frac.of(coef),
            "(${lin(a, b)})(${lin(c, d)}) = ${a * c}x² + ${p(a * d)}x + ${p(b * c)}x + ${p(b * d)}",
            "Coefficient of x: ${s(a * d)} + ${p(b * c)} = $coef")
    }

    private fun quadraticRoot(): Question {
        val m = rnd(2, 12); val n = rnd(1, 12)    // (x − m)(x + n) = 0，正根是 m
        if (m == n) return quadraticRoot()
        val b = n - m; val c = -m * n
        val eq = "x² " + (if (b > 0) "+ ${if (b == 1) "" else "$b"}x " else if (b < 0) "− ${if (b == -1) "" else "${-b}"}x " else "") + "− ${-c} = 0"
        return q("Quadratic equations",
            "Solve $eq.\nGive the POSITIVE root.",
            Frac.of(m),
            "Factorise: (x − $m)(x + $n) = 0",
            "x = $m or x = −$n",
            "The positive root is $m")
    }

    private fun substitution(): Question {
        val a = nz(-5, 6); val b = nz(-5, 6)
        val k1 = rnd(1, 4); val k2 = rnd(1, 5); val k3 = rnd(1, 3)
        val ans = k1 * a * a - k2 * a * b + k3 * b * b
        val c1 = if (k1 == 1) "" else "$k1"; val c3 = if (k3 == 1) "" else "$k3"
        return q("Algebraic expressions",
            "Given that a = ${s(a)} and b = ${s(b)}, find the value of\n  ${c1}a² − ${k2}ab + ${c3}b²",
            Frac.of(ans),
            "${c1}a² = $k1 × ${p(a)}² = ${k1 * a * a}",
            "${k2}ab = $k2 × ${p(a)} × ${p(b)} = ${s(k2 * a * b)}",
            "${c3}b² = $k3 × ${p(b)}² = ${k3 * b * b}",
            "${k1 * a * a} − ${p(k2 * a * b)} + ${k3 * b * b} = $ans")
    }

    // ---------------- Geometry & graphs ----------------

    private fun pythagoras(): Question {
        val (x, y, z) = pick(Triple(3, 4, 5), Triple(5, 12, 13), Triple(8, 15, 17), Triple(7, 24, 25), Triple(20, 21, 29))
        val k = if (z * 4 <= 60) rnd(1, 4) else rnd(1, 2)
        val a = x * k; val b = y * k; val c = z * k
        return if (r.nextBoolean()) q("Pythagoras' theorem",
            "A right-angled triangle has two shorter sides of $a cm and $b cm.\nFind the length of the longest side (hypotenuse) in cm.",
            Frac.of(c),
            "c² = $a² + $b² = ${a * a} + ${b * b} = ${c * c}",
            "c = √${c * c} = $c")
        else q("Pythagoras' theorem",
            "A ladder $c m long leans against a vertical wall. Its foot is $a m from the wall on level ground.\nHow high up the wall does the ladder reach, in m?",
            Frac.of(b),
            "h² = $c² − $a² = ${c * c} − ${a * a} = ${b * b}",
            "h = √${b * b} = $b")
    }

    private fun gradient(): Question {
        while (true) {
            val x1 = rnd(-6, 6); val y1 = rnd(-6, 6)
            val dx = nz(-6, 6); val dy = nz(-8, 8)
            val m = Frac.of(dy, dx)
            if (!m.isFiniteDecimal) continue
            val x2 = x1 + dx; val y2 = y1 + dy
            return q("Gradient",
                "Find the gradient of the straight line passing through the points (${s(x1)}, ${s(y1)}) and (${s(x2)}, ${s(y2)}).\n(You may answer as a fraction or a decimal.)",
                m,
                "Gradient = (y₂ − y₁) ÷ (x₂ − x₁)",
                "= (${s(y2)} − ${p(y1)}) ÷ (${s(x2)} − ${p(x1)}) = ${s(dy)} ÷ ${p(dx)} = $m")
        }
    }

    private fun yIntercept(): Question {
        val m = nz(-5, 5); val c = nz(-12, 12); val x = nz(-5, 6)
        val y = m * x + c
        return q("Straight-line graphs",
            "The line y = ${lin(m, 0)} + c passes through the point (${s(x)}, ${s(y)}).\nFind the value of c.",
            Frac.of(c),
            "Substitute x = ${s(x)}, y = ${s(y)}: ${s(y)} = ${s(m)} × ${p(x)} + c",
            "${s(y)} = ${s(m * x)} + c",
            "c = ${s(y)} − ${p(m * x)} = $c")
    }

    private fun polygon(): Question {
        val nSides = pick(5, 6, 8, 9, 10, 12, 15, 18, 20)
        val interior = 180 - 360 / nSides
        return if (r.nextBoolean()) q("Polygons",
            "Each interior angle of a regular polygon is $interior°.\nHow many sides does the polygon have?",
            Frac.of(nSides),
            "Each exterior angle = 180° − $interior° = ${180 - interior}°",
            "Number of sides = 360° ÷ ${180 - interior}° = $nSides")
        else q("Polygons",
            "Find the sum of the interior angles of a polygon with $nSides sides, in degrees.",
            Frac.of((nSides - 2) * 180),
            "Sum of interior angles = (n − 2) × 180°",
            "= ($nSides − 2) × 180° = ${(nSides - 2) * 180}")
    }

    private fun cylinder(): Question {
        val rad = pick(7, 14, 21, 28, 35)   // 半径是 7 的倍数，用 22/7 算出来是整数
        val h = rnd(2, 20)
        val v = Frac.of(22, 7) * (rad * rad * h)
        return q("Volume of cylinder",
            "A cylinder has a radius of $rad cm and a height of $h cm.\nFind its volume in cm³. (Take π = 22/7)",
            v,
            "Volume = πr²h = 22/7 × $rad² × $h",
            "= 22/7 × ${rad * rad} × $h = $v")
    }

    // ---------------- Numbers, percentage & rate ----------------

    private fun percentageChange(): Question {
        val old = rnd(4, 40) * 5
        val pct = pick(5, 10, 12, 15, 20, 25, 30, 40, 50)
        val up = r.nextBoolean()
        val change = Frac.of(old * pct, 100)
        val new = if (up) Frac.of(old) + change else Frac.of(old) - change
        val word = if (up) "increase" else "decrease"
        return q("Percentage",
            "The price of a bag changed from ${Frac.of(old).toMoney()} to ${new.toMoney()}.\nFind the percentage $word.",
            Frac.of(pct),
            "Change = ${new.toMoney()} − ${Frac.of(old).toMoney()} = ${if (up) "" else "−"}${change.toMoney()}",
            "Percentage $word = ${change} ÷ $old × 100% = $pct")
    }

    private fun reversePercentage(): Question {
        val pct = pick(10, 15, 20, 25, 30, 40)
        val orig = rnd(4, 40) * 20
        val after = Frac.of(orig) * Frac.of(100 - pct, 100)
        return q("Percentage",
            "After a discount of $pct%, a pair of shoes costs ${after.toMoney()}.\nWhat was the original price in dollars?",
            Frac.of(orig),
            "${100 - pct}% of the original price = ${after.toMoney()}",
            "1% = ${after.toMoney()} ÷ ${100 - pct} = ${(after / (100 - pct)).toMoney()}",
            "100% = ${(after / (100 - pct)).toMoney()} × 100 = $orig")
    }

    private fun proportion(): Question {
        return if (r.nextBoolean()) {
            val k = Frac.of(rnd(2, 12), pick(1, 2, 4))
            val x1 = rnd(2, 8) * 4; val x2 = rnd(2, 12) * 4
            if (x1 == x2) return proportion()
            val y1 = k * x1; val y2 = k * x2
            q("Direct proportion",
                "y is directly proportional to x. When x = $x1, y = $y1.\nFind y when x = $x2.",
                y2,
                "y = kx, so k = $y1 ÷ $x1 = $k",
                "When x = $x2, y = $k × $x2 = $y2")
        } else {
            val k = pick(24, 36, 48, 60, 72, 120)
            val divs = (1..k).filter { k % it == 0 && it in 2..k / 2 }
            val x1 = divs.random(r); var x2: Int
            do { x2 = divs.random(r) } while (x2 == x1)
            q("Inverse proportion",
                "y is inversely proportional to x. When x = $x1, y = ${k / x1}.\nFind y when x = $x2.",
                Frac.of(k / x2),
                "y = k/x, so k = $x1 × ${k / x1} = $k",
                "When x = $x2, y = $k ÷ $x2 = ${k / x2}")
        }
    }

    private fun median(): Question {
        val count = pick(6, 7, 8)
        val xs = MutableList(count) { rnd(10, 60) }
        val sorted = xs.sorted()
        val med = if (count % 2 == 1) Frac.of(sorted[count / 2])
        else Frac.of(sorted[count / 2 - 1] + sorted[count / 2], 2)
        return if (r.nextBoolean()) q("Statistics",
            "Find the median of these numbers:\n  ${xs.joinToString(", ")}",
            med,
            "In order: ${sorted.joinToString(", ")}",
            if (count % 2 == 1) "The middle number is $med"
            else "Median = (${sorted[count / 2 - 1]} + ${sorted[count / 2]}) ÷ 2 = $med")
        else {
            val sum = xs.sum()
            val mean = Frac.of(sum, count)
            if (!mean.isFiniteDecimal || mean.decimalPlaces > 2) return median()
            q("Statistics",
                "Find the mean of these numbers:\n  ${xs.joinToString(", ")}",
                mean,
                "Sum = ${xs.joinToString(" + ")} = $sum",
                "Mean = $sum ÷ $count = $mean")
        }
    }

    private fun probability(): Question {
        val red = rnd(2, 9); val blue = rnd(2, 9); val green = rnd(1, 8)
        val total = red + blue + green
        val ans = Frac.of(blue + green, total)
        return Question("Probability",
            "A bag contains $red red, $blue blue and $green green balls. A ball is picked at random.\n" +
                "Find the probability that it is NOT red. (Answer as a fraction, e.g. 3/5)",
            ans.toFracString(),
            listOf(
                "Total balls = $red + $blue + $green = $total",
                "Not red = $blue + $green = ${blue + green}",
                "P(not red) = ${blue + green}/$total = ${ans.toFracString()}",
            ).joinToString("\n"),
            answer = ans, level = LEVEL)
    }

    private fun averageSpeed(): Question {
        val speed = rnd(8, 24) * 5
        val minutes = pick(15, 20, 30, 40, 45, 50, 90, 75, 105)
        val dist = Frac.of(speed * minutes, 60)
        if (!dist.isFiniteDecimal || dist.decimalPlaces > 1) return averageSpeed()
        val h = minutes / 60; val mm = minutes % 60
        val time = (if (h > 0) "$h h " else "") + (if (mm > 0) "$mm min" else "")
        return q("Speed",
            "A car travels $dist km in ${time.trim()}.\nFind its average speed in km/h.",
            Frac.of(speed),
            "${time.trim()} = $minutes/60 h = ${Frac.of(minutes, 60)} h",
            "Speed = $dist ÷ ${Frac.of(minutes, 60)} = $speed")
    }

    private fun inequality(): Question {
        val a = rnd(3, 9); val c = rnd(1, a - 1)
        val b = nz(-15, 15); val d = rnd(-10, 30)
        // a x + b < c x + d  →  x < (d − b)/(a − c)
        val bound = Frac.of(d - b, a - c)
        val largest = Math.floorDiv(d - b - 1, a - c)   // 严格小于时的最大整数
        return q("Inequalities",
            "Find the largest integer x that satisfies  ${lin(a, b)} < ${lin(c, d)}.",
            Frac.of(largest),
            "${lin(a - c, 0)} < ${s(d - b)}",
            "x < ${s(bound)}",
            "The largest integer is $largest")
    }

    private fun simpleInterest(): Question {
        val principal = rnd(2, 20) * 500
        val rate = pick(2, 3, 4, 5, 6)
        val years = rnd(2, 6)
        val interest = principal * rate * years / 100
        return q("Simple interest",
            "Mr Lim invests ${Frac.of(principal).toMoney()} at $rate% per annum simple interest for $years years.\n" +
                "How much will he have at the end, in dollars?",
            Frac.of(principal + interest),
            "Interest = ${Frac.of(principal).toMoney()} × $rate% × $years = ${Frac.of(interest).toMoney()}",
            "Total = ${Frac.of(principal).toMoney()} + ${Frac.of(interest).toMoney()} = ${principal + interest}")
    }
}
