package com.krow

/** Small unified-diff generator (prefix/suffix trim + LCS on the middle). Used by edit_file and the git tools. */
object Diff {
    private class Op(val t: Char, val line: String)

    private fun lines(s: String): List<String> {
        if (s.isEmpty()) return emptyList()
        val l = s.split("\n")
        return if (s.endsWith("\n")) l.dropLast(1) else l
    }

    private fun ops(a: List<String>, b: List<String>, maxCells: Long): List<Op> {
        var p = 0
        while (p < a.size && p < b.size && a[p] == b[p]) p++
        var s = 0
        while (s < a.size - p && s < b.size - p && a[a.size - 1 - s] == b[b.size - 1 - s]) s++
        val am = a.subList(p, a.size - s)
        val bm = b.subList(p, b.size - s)
        val out = ArrayList<Op>()
        for (i in 0 until p) out += Op(' ', a[i])
        if (am.isEmpty()) bm.forEach { out += Op('+', it) }
        else if (bm.isEmpty()) am.forEach { out += Op('-', it) }
        else if (am.size.toLong() * bm.size > maxCells) {
            am.forEach { out += Op('-', it) }; bm.forEach { out += Op('+', it) }
        } else {
            val n = am.size; val m = bm.size; val w = m + 1
            val dp = IntArray((n + 1) * w)
            for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
                dp[i * w + j] = if (am[i] == bm[j]) dp[(i + 1) * w + j + 1] + 1 else maxOf(dp[(i + 1) * w + j], dp[i * w + j + 1])
            }
            var i = 0; var j = 0
            while (i < n && j < m) {
                if (am[i] == bm[j]) { out += Op(' ', am[i]); i++; j++ }
                else if (dp[(i + 1) * w + j] >= dp[i * w + j + 1]) { out += Op('-', am[i]); i++ }
                else { out += Op('+', bm[j]); j++ }
            }
            while (i < n) { out += Op('-', am[i]); i++ }
            while (j < m) { out += Op('+', bm[j]); j++ }
        }
        for (i in a.size - s until a.size) out += Op(' ', a[i])
        return out
    }

    /** Returns "" when identical. */
    fun unified(path: String, oldText: String, newText: String, ctx: Int = 3, maxCells: Long = 4_000_000L): String {
        if (oldText == newText) return ""
        val ops = ops(lines(oldText), lines(newText), maxCells)
        val changed = ops.indices.filter { ops[it].t != ' ' }
        if (changed.isEmpty()) return ""
        val oldBefore = IntArray(ops.size + 1); val newBefore = IntArray(ops.size + 1)
        for (i in ops.indices) {
            oldBefore[i + 1] = oldBefore[i] + if (ops[i].t != '+') 1 else 0
            newBefore[i + 1] = newBefore[i] + if (ops[i].t != '-') 1 else 0
        }
        val groups = ArrayList<IntRange>()
        var gs = changed[0]; var ge = changed[0]
        for (k in 1 until changed.size) {
            if (changed[k] - ge > 2 * ctx + 1) { groups += gs..ge; gs = changed[k] }
            ge = changed[k]
        }
        groups += gs..ge
        val sb = StringBuilder()
        sb.append("--- a/").append(path).append('\n').append("+++ b/").append(path).append('\n')
        for (g in groups) {
            val from = maxOf(0, g.first - ctx); val to = minOf(ops.lastIndex, g.last + ctx)
            var oc = 0; var nc = 0
            for (i in from..to) { if (ops[i].t != '+') oc++; if (ops[i].t != '-') nc++ }
            val os = if (oc == 0) oldBefore[from] else oldBefore[from] + 1
            val ns = if (nc == 0) newBefore[from] else newBefore[from] + 1
            sb.append("@@ -").append(os).append(',').append(oc).append(" +").append(ns).append(',').append(nc).append(" @@\n")
            for (i in from..to) sb.append(ops[i].t).append(ops[i].line).append('\n')
        }
        return sb.toString().trimEnd('\n')
    }

    fun counts(diff: String): Pair<Int, Int> {
        var a = 0; var d = 0
        for (l in diff.lineSequence()) {
            if (l.startsWith("+") && !l.startsWith("+++")) a++
            else if (l.startsWith("-") && !l.startsWith("---")) d++
        }
        return a to d
    }
}
