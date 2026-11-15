package com.kotlinsun.hitchtalk.domain

import com.kotlinsun.hitchtalk.data.SessionRecord

object ReportComparison {
    fun previousMedian(
        report: SessionRecord,
        history: List<SessionRecord>,
        metric: (SessionRecord) -> Int?,
    ): Int? {
        if (!report.scores.sampleSufficient || report.endedAtMillis <= 0L) return null
        val values = history.asSequence()
            .filter {
                it.id != report.id && it.endedAtMillis in 1L until report.endedAtMillis &&
                    it.scores.sampleSufficient && it.scores.scoringVersion == report.scores.scoringVersion
            }
            .sortedByDescending { it.endedAtMillis }
            .distinctBy { it.id }
            .take(5)
            .mapNotNull(metric)
            .sorted()
            .toList()
        if (values.isEmpty()) return null
        val midpoint = values.size / 2
        // Keep the baseline in whole points to match the score shown in the report.
        return if (values.size % 2 == 0) (values[midpoint - 1] + values[midpoint]) / 2 else values[midpoint]
    }

    fun change(
        report: SessionRecord,
        history: List<SessionRecord>,
        metric: (SessionRecord) -> Int?,
    ): Int? {
        val score = metric(report) ?: return null
        return previousMedian(report, history, metric)?.let { score - it }
    }
}
