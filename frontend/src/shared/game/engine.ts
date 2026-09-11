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
  RED: { levels: 12, baseHazard: 0.12, hazardGrowth: 1.115, maxHazard: 0.6, rtp: 0.96 },
  GREEN: { levels: 9, baseHazard: 0.155, hazardGrowth: 1.12, maxHazard: 0.6, rtp: 0.96 }
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

/** Первый уровень, на котором шар лопается; levels + 1 — шар долетел до вершины. */
export function rollPopLevel(config: EngineConfig): number {
  for (let level = 1; level <= config.levels; level++) {
    if (Math.random() < hazardAt(config, level)) return level;
  }
  return config.levels + 1;
}
