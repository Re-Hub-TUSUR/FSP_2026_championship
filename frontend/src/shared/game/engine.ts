import type { Theme } from '../api/client';

export const MAX_MULTIPLIER = 10_000;

export interface EngineConfig {
  levels: number;
  baseHazard: number;
  hazardGrowth: number;
  maxHazard: number;
  rtp: number;
}

/** Зеркало Config.defaults на бэкенде: используется, когда API недоступен. */
export const ENGINE_CONFIG: Record<Theme, EngineConfig> = {
  RED: { levels: 12, baseHazard: 0.07, hazardGrowth: 1.2, maxHazard: 0.6, rtp: 0.96 },
  GREEN: { levels: 9, baseHazard: 0.05, hazardGrowth: 1.35, maxHazard: 0.6, rtp: 0.96 }
};

/** Шанс взрыва на уровне растёт экспоненциально вместе с давлением в шаре. */
export const hazardAt = (config: EngineConfig, level: number) =>
  Math.min(config.maxHazard, config.baseHazard * Math.pow(config.hazardGrowth, level - 1));

/** Коэффициент уровня обратен вероятности до него долететь — отсюда экспоненциальный рост. */
export function buildLadder(config: EngineConfig): number[] {
  const ladder = [1];
  let survival = 1;
  for (let level = 1; level <= config.levels; level++) {
    survival *= 1 - hazardAt(config, level);
    ladder.push(Math.min(MAX_MULTIPLIER, Math.round(Math.max(ladder[level - 1] + 0.01, config.rtp / survival) * 100) / 100));
  }
  return ladder;
}

/**
 * Ожидаемая прибавка коэффициента от бустера, одинаковая для всех тиров:
 * (множитель − 1) × шанс появления = BOOSTER_BUDGET. Зеркало Models.BOOSTER_BUDGET.
 */
export const BOOSTER_BUDGET = 0.5;

/** Шанс, что бустер вообще появится в раунде: сильный бустер выпадает реже. */
export const boosterAppearChance = (booster: number) => (booster <= 1 ? 0 : Math.min(1, BOOSTER_BUDGET / (booster - 1)));

/** Бустер не выпадает на первом уровне. Зеркало Config.defaultBoosterWeights. */
export function defaultBoosterWeights(levels: number): number[] {
  if (levels < 2) return [1];
  return [0, ...Array(levels - 1).fill(1 / (levels - 1))];
}

/**
 * Лестница для ставки с бустером: ожидаемый вклад бустера заранее вычитается из базовых
 * коэффициентов, поэтому возврат игроку одинаков для всех вариантов ставки.
 * Зеркало Config.ladderForBooster на бэкенде.
 */
export function ladderForBooster(config: EngineConfig, booster: number): number[] {
  const base = buildLadder(config);
  if (booster <= 1) return base;
  const weights = defaultBoosterWeights(config.levels);
  const priced = [1];
  let reached = 0;
  for (let level = 1; level <= config.levels; level++) {
    reached += weights[level - 1];
    const value = Math.round((base[level] / (1 + BOOSTER_BUDGET * reached)) * 100) / 100;
    priced.push(Math.max(priced[level - 1] + 0.01, value));
  }
  return priced;
}

/** Первый уровень, на котором шар лопается; levels + 1 — шар долетел до вершины. */
export function rollPopLevel(config: EngineConfig): number {
  for (let level = 1; level <= config.levels; level++) {
    if (Math.random() < hazardAt(config, level)) return level;
  }
  return config.levels + 1;
}
