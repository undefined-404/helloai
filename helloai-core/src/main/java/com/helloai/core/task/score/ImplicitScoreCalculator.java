package com.helloai.core.task.score;

import com.helloai.core.task.entity.ReviewRecord;
import com.helloai.core.task.entity.SubTask;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Slf4j
@Component
public class ImplicitScoreCalculator {

    @Data
    @Builder
    public static class ScoreResult {
        private ScoreFactors factors;
        private Integer compositeScore;
        private String grade;
        private Integer rewardDelta;
    }

    @Data
    public static class ScoreFactors {
        private Integer timeScore;
        private Integer qualityScore;
        private Integer coopScore;
        private Integer stabilityScore;
        private Integer efficiencyScore;
        private Integer reworkCount;
        private Integer blockCount;
        private Integer timeoutCount;
        private Long actualDurationMs;
        private Long deadlineMs;
    }

    public ScoreResult calculate(SubTask subTask, List<ReviewRecord> reviews, int blockCount, int timeoutCount) {
        ScoreFactors factors = new ScoreFactors();

        long actualMs = Duration.between(subTask.getCreateTime(), subTask.getUpdateTime()).toMillis();
        long deadlineMs = subTask.getDeadline() != null
                ? Duration.between(subTask.getCreateTime(), subTask.getDeadline()).toMillis()
                : Math.max(actualMs * 2, 60000);

        factors.setActualDurationMs(actualMs);
        factors.setDeadlineMs(deadlineMs);
        factors.setTimeScore(calculateTimeScore(actualMs, deadlineMs));
        factors.setQualityScore(calculateQualityScore(reviews));

        long reworkCount = reviews.stream().filter(r -> "REJECTED".equals(r.getResult().name())).count();
        factors.setReworkCount((int) reworkCount);
        factors.setCoopScore(Math.max(0, 100 - (int) reworkCount * 20));

        factors.setBlockCount(blockCount);
        factors.setTimeoutCount(timeoutCount);
        factors.setStabilityScore(Math.max(0, 100 - blockCount * 15 - timeoutCount * 10));
        factors.setEfficiencyScore(100);

        int composite = (int) Math.round(
                factors.getTimeScore() * 0.25
                        + factors.getQualityScore() * 0.30
                        + factors.getCoopScore() * 0.25
                        + factors.getStabilityScore() * 0.15
                        + factors.getEfficiencyScore() * 0.05
        );

        String grade = resolveGrade(composite);
        Integer rewardDelta = resolveRewardDelta(grade);

        return ScoreResult.builder()
                .factors(factors)
                .compositeScore(composite)
                .grade(grade)
                .rewardDelta(rewardDelta)
                .build();
    }

    private int calculateTimeScore(long actualMs, long deadlineMs) {
        if (actualMs <= deadlineMs) {
            double ratio = 1.0 - (double) actualMs / deadlineMs;
            return (int) Math.round(75 + 25 * ratio);
        } else {
            double overrun = (double) (actualMs - deadlineMs) / deadlineMs;
            return Math.max(0, (int) Math.round(75 - 50 * overrun));
        }
    }

    private int calculateQualityScore(List<ReviewRecord> reviews) {
        if (reviews == null || reviews.isEmpty()) return 75;
        double avg = reviews.stream().mapToInt(ReviewRecord::getScore).average().orElse(3);
        return (int) Math.round((avg - 1) / 4.0 * 100);
    }

    private String resolveGrade(int score) {
        if (score >= 90) return "S";
        if (score >= 80) return "A";
        if (score >= 60) return "B";
        if (score >= 40) return "C";
        return "D";
    }

    private Integer resolveRewardDelta(String grade) {
        return switch (grade) {
            case "S" -> 5;
            case "A" -> 3;
            case "B" -> 0;
            case "C" -> -3;
            case "D" -> -5;
            default -> 0;
        };
    }
}
