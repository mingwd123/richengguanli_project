package com.dayliane.fatigue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class FatigueModelCalibrator {
    static final BigDecimal DEFAULT_CAPACITY = BigDecimal.valueOf(18);
    private static final double[] DEFAULT_WEIGHTS = {1, 2, 3, 5, 8};
    private static final int MIN_CAPACITY_DAYS = 7;
    private static final int PERSONALIZED_DAYS = 21;
    private static final int MIN_WEIGHT_DAYS = 30;

    private FatigueModelCalibrator() {
    }

    static Result calibrate(List<Sample> samples,
                            BigDecimal currentCapacity,
                            Map<Integer, BigDecimal> currentWeights,
                            boolean capacityLocked,
                            boolean learningEnabled,
                            boolean weightFitDue) {
        List<Sample> ordered = samples.stream()
                .sorted(Comparator.comparing(Sample::localDate))
                .toList();
        String stage = stage(ordered);
        Map<LocalDate, BigDecimal> capacityAfter = new LinkedHashMap<>();
        BigDecimal capacity = capacityLocked ? currentCapacity : DEFAULT_CAPACITY;

        if (learningEnabled && !capacityLocked) {
            for (int index = 0; index < ordered.size(); index++) {
                if (index + 1 < MIN_CAPACITY_DAYS) continue;
                int from = Math.max(0, index - 27);
                List<WeightedValue> candidates = new ArrayList<>();
                int windowSize = index - from + 1;
                for (int cursor = from; cursor <= index; cursor++) {
                    Sample sample = ordered.get(cursor);
                    int boundedScore = Math.max(10, Math.min(95, sample.score()));
                    BigDecimal candidate = BigDecimal.valueOf(75)
                            .multiply(sample.completedLoad())
                            .divide(BigDecimal.valueOf(boundedScore), 6, RoundingMode.HALF_UP)
                            .max(capacity.multiply(BigDecimal.valueOf(.5)))
                            .min(capacity.multiply(BigDecimal.valueOf(1.5)));
                    double recency = windowSize == 1 ? 1D : 1D + (double) (cursor - from) / (windowSize - 1);
                    candidates.add(new WeightedValue(candidate, sample.learningWeight().doubleValue() * recency));
                }
                BigDecimal median = weightedMedian(candidates);
                capacity = boundedCapacityStep(capacity, median);
                capacityAfter.put(ordered.get(index).localDate(), capacity);
            }
        }

        Map<Integer, BigDecimal> weights = capacityLocked || !learningEnabled
                ? new LinkedHashMap<>(currentWeights)
                : defaultWeights();
        boolean weightsAdjusted = false;
        if (learningEnabled && !capacityLocked && weightFitDue && weightFitEligible(ordered)) {
            Map<Integer, BigDecimal> fitted = fitWeights(ordered, capacity);
            weightsAdjusted = !sameWeights(currentWeights, fitted);
            weights = fitted;
        } else if (learningEnabled && !capacityLocked && weightFitEligible(ordered)) {
            weights = new LinkedHashMap<>(currentWeights);
        }

        return new Result(scale(capacity), weights, stage, confidence(ordered), capacityAfter, weightsAdjusted);
    }

    private static BigDecimal weightedMedian(List<WeightedValue> values) {
        if (values.isEmpty()) return DEFAULT_CAPACITY;
        List<WeightedValue> ordered = values.stream().sorted(Comparator.comparing(WeightedValue::value)).toList();
        double total = ordered.stream().mapToDouble(WeightedValue::weight).sum();
        double cursor = 0D;
        for (WeightedValue value : ordered) {
            cursor += value.weight();
            if (cursor >= total / 2D) return value.value();
        }
        return ordered.get(ordered.size() - 1).value();
    }

    private static BigDecimal boundedCapacityStep(BigDecimal current, BigDecimal candidate) {
        BigDecimal smoothed = current.multiply(BigDecimal.valueOf(.85))
                .add(candidate.multiply(BigDecimal.valueOf(.15)));
        return smoothed
                .max(current.multiply(BigDecimal.valueOf(.9)))
                .min(current.multiply(BigDecimal.valueOf(1.1)))
                .max(BigDecimal.valueOf(5))
                .min(BigDecimal.valueOf(60))
                .setScale(3, RoundingMode.HALF_UP);
    }

    private static boolean weightFitEligible(List<Sample> samples) {
        if (samples.size() < MIN_WEIGHT_DAYS || spanDays(samples) < 27) return false;
        int[] daysByLevel = new int[5];
        for (Sample sample : samples) {
            for (int index = 0; index < 5; index++) {
                if (sample.levelCounts()[index] > 0) daysByLevel[index]++;
            }
        }
        for (int days : daysByLevel) if (days < 5) return false;
        return true;
    }

    private static Map<Integer, BigDecimal> fitWeights(List<Sample> samples, BigDecimal capacity) {
        double[][] matrix = new double[5][6];
        double regularization = 4D;
        for (Sample sample : samples) {
            double sampleWeight = sample.learningWeight().doubleValue();
            double targetLoad = Math.max(10, Math.min(95, sample.score())) * capacity.doubleValue() / 75D;
            for (int row = 0; row < 5; row++) {
                double xRow = sample.levelCounts()[row];
                matrix[row][5] += sampleWeight * xRow * targetLoad;
                for (int column = 0; column < 5; column++) {
                    matrix[row][column] += sampleWeight * xRow * sample.levelCounts()[column];
                }
            }
        }
        for (int index = 0; index < 5; index++) {
            matrix[index][index] += regularization;
            matrix[index][5] += regularization * DEFAULT_WEIGHTS[index];
        }
        double[] solved = solve(matrix);
        if (solved == null) solved = DEFAULT_WEIGHTS.clone();
        constrainWeights(solved);
        Map<Integer, BigDecimal> result = new LinkedHashMap<>();
        for (int index = 0; index < 5; index++) {
            result.put(index + 1, BigDecimal.valueOf(solved[index]).setScale(3, RoundingMode.HALF_UP));
        }
        return result;
    }

    private static double[] solve(double[][] augmented) {
        int size = 5;
        for (int pivot = 0; pivot < size; pivot++) {
            int best = pivot;
            for (int row = pivot + 1; row < size; row++) {
                if (Math.abs(augmented[row][pivot]) > Math.abs(augmented[best][pivot])) best = row;
            }
            if (Math.abs(augmented[best][pivot]) < 1e-9) return null;
            double[] swap = augmented[pivot];
            augmented[pivot] = augmented[best];
            augmented[best] = swap;
            double divisor = augmented[pivot][pivot];
            for (int column = pivot; column <= size; column++) augmented[pivot][column] /= divisor;
            for (int row = 0; row < size; row++) {
                if (row == pivot) continue;
                double factor = augmented[row][pivot];
                for (int column = pivot; column <= size; column++) augmented[row][column] -= factor * augmented[pivot][column];
            }
        }
        double[] result = new double[size];
        for (int index = 0; index < size; index++) result[index] = augmented[index][size];
        return result;
    }

    private static void constrainWeights(double[] weights) {
        for (int index = 0; index < weights.length; index++) {
            double lower = DEFAULT_WEIGHTS[index] * .7D;
            double upper = DEFAULT_WEIGHTS[index] * 1.3D;
            weights[index] = Math.max(lower, Math.min(upper, weights[index]));
        }
        for (int index = 1; index < weights.length; index++) {
            weights[index] = Math.max(weights[index], weights[index - 1] + .001D);
        }
        for (int index = weights.length - 2; index >= 0; index--) {
            weights[index] = Math.min(weights[index], weights[index + 1] - .001D);
        }
    }

    private static boolean sameWeights(Map<Integer, BigDecimal> left, Map<Integer, BigDecimal> right) {
        for (int level = 1; level <= 5; level++) {
            BigDecimal a = left.getOrDefault(level, BigDecimal.valueOf(DEFAULT_WEIGHTS[level - 1]));
            BigDecimal b = right.getOrDefault(level, BigDecimal.valueOf(DEFAULT_WEIGHTS[level - 1]));
            if (a.compareTo(b) != 0) return false;
        }
        return true;
    }

    private static Map<Integer, BigDecimal> defaultWeights() {
        Map<Integer, BigDecimal> result = new LinkedHashMap<>();
        for (int index = 0; index < DEFAULT_WEIGHTS.length; index++) {
            result.put(index + 1, BigDecimal.valueOf(DEFAULT_WEIGHTS[index]));
        }
        return result;
    }

    private static String stage(List<Sample> samples) {
        if (samples.size() < MIN_CAPACITY_DAYS) return "default";
        if (samples.size() >= PERSONALIZED_DAYS && spanDays(samples) >= 20) return "personalized";
        return "calibrating";
    }

    private static String confidence(List<Sample> samples) {
        if (samples.size() < MIN_CAPACITY_DAYS) return "low";
        if (samples.size() >= PERSONALIZED_DAYS && spanDays(samples) >= 20) return "high";
        return "medium";
    }

    private static long spanDays(List<Sample> samples) {
        if (samples.size() < 2) return 0;
        return ChronoUnit.DAYS.between(samples.get(0).localDate(), samples.get(samples.size() - 1).localDate());
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(3, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    record Sample(LocalDate localDate, int score, BigDecimal completedLoad, BigDecimal learningWeight, int[] levelCounts) {
    }

    record Result(BigDecimal capacity,
                  Map<Integer, BigDecimal> weights,
                  String stage,
                  String confidence,
                  Map<LocalDate, BigDecimal> capacityAfter,
                  boolean weightsAdjusted) {
    }

    private record WeightedValue(BigDecimal value, double weight) {
    }
}
