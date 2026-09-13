import type { Theme } from '../api/client';

export const MAX_MULTIPLIER = 10_000;

/** Первый подъём без риска. Зеркало Models.SAFE_LEVELS, MIN_CASHOUT_LEVEL и FIRST_BOOSTER_LEVEL. */
export const SAFE_LEVELS = 1;
export const MIN_CASHOUT_LEVEL = SAFE_LEVELS + 1;
export const FIRST_BOOSTER_LEVEL = MIN_CASHOUT_LEVEL + 1;

export interface EngineConfig {
  levels: number;
  baseHazard: number;
  hazardGrowth: number;
  maxHazard: number;
  rtp: number;
}

/** Зеркало Config.defaults на бэкенде: используется, когда API недоступен. */
export const ENGINE_CONFIG: Record<Theme, EngineConfig> = {
  RED: { levels: 12, baseHazard: 0.15, hazardGrowth: 1.14, maxHazard: 0.6, rtp: 0.96 },
  GREEN: { levels: 9, baseHazard: 0.1, hazardGrowth: 1.25, maxHazard: 0.6, rtp: 0.96 }
};

/** Первый подъём без риска, дальше шанс взрыва растёт экспоненциально вместе с давлением в шаре. */
export const hazardAt = (config: EngineConfig, level: number) =>
  level <= SAFE_LEVELS ? 0 : Math.min(config.maxHazard, config.baseHazard * Math.pow(config.hazardGrowth, level - 1));

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

/** Бустер выпадает равновероятно начиная с FIRST_BOOSTER_LEVEL. Зеркало Config.defaultBoosterWeights. */
export function defaultBoosterWeights(levels: number): number[] {
  const first = FIRST_BOOSTER_LEVEL - 1;
  if (levels <= first) return Array(Math.max(1, levels)).fill(1 / Math.max(1, levels));
  return [...Array(first).fill(0), ...Array(levels - first).fill(1 / (levels - first))];
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
