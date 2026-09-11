package com.example.balloon.application;

/** Server-side multiplier policy. Values are deliberately bounded to prevent abrupt jumps. */
public record StrategyConfig(
        double baseStep,
        double levelStep,
        double maxStep,
        double maxMultiplier,
        int minLevel,
        int maxLevel) {
    public static StrategyConfig conservative(){ return new StrategyConfig(0.14, 0.008, 0.24, 4.5, 1, 30); }
    public static StrategyConfig balanced(){ return new StrategyConfig(0.20, 0.012, 0.34, 6.5, 1, 30); }
    public static StrategyConfig aggressive(){ return new StrategyConfig(0.27, 0.018, 0.48, 9.0, 1, 30); }
}
