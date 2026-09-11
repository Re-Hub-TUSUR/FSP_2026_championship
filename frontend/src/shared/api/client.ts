import { ENGINE_CONFIG, buildLadder, hazardAt, rollPopLevel } from '../game/engine';

export type Theme = 'RED' | 'GREEN';
export interface Me { id?: string; username: string; balance: number; role: string; points?: number; level?: number; blocked?: boolean }
export interface LeaderRow { id: string; username: string; balance: number; rank?: number; level: number; points: number; multiplier: number; trend: number; rounds?: number; cashouts?: number; updatedAt?: string }
export interface AdminUser { id: string; username: string; role: string; blocked: boolean; balance: number; points: number; level: number; createdAt?: string }
export interface BetOption { id: string; amount: number; boosterMultiplier: number }
export interface LevelRow { level: number; multiplier: number; popChance: number }
export interface GameConfig {
  gameId: string; gameName?: string; theme: Theme; levels: number; betOptions: BetOption[];
  pointsPerLevel: number; pointsCashoutBonus?: number; pointsBoosterBonus?: number;
  ladder?: number[]; levelTable?: LevelRow[]; maxMultiplier?: number; rtp?: number; version?: number;
}
export interface Round {
  roundId: string; state: string; theme: Theme; betAmount: number; boosterMultiplier: number;
  boosterLevel?: number; boosterActivated?: boolean; levels: number; level: number; multiplier: number;
  nextMultiplier?: number; ladder?: number[]; potentialPayout?: number; cashedOutMultiplier?: number;
  payout?: number; profit?: number; points: number; balance?: number; balanceAfter?: number;
  popLevel?: number; commitment?: string; createdAt?: string; endedAt?: string; serverTime?: string;
}

const API = (import.meta.env.VITE_API_URL as string | undefined) || '/api/v1';

const POINTS_PER_LEVEL = 10;
const POINTS_CASHOUT_BONUS = 20;
const POINTS_BOOSTER_BONUS = 15;
const START_BALANCE = 1000;
const LEVEL_DURATION_MS = 1000;

const roundsKey = 'balloon-demo-rounds';
const balanceKey = 'balloon-demo-balance';

type DemoRound = Round & { popLevel: number; ladder: number[]; boosterLevel: number; startedAtMs: number };

const loadRounds = (): DemoRound[] => { try { return JSON.parse(localStorage.getItem(roundsKey) || '[]'); } catch { return []; } };
const saveRounds = (rows: DemoRound[]) => localStorage.setItem(roundsKey, JSON.stringify(rows.slice(-50)));
const loadBalance = () => Number(localStorage.getItem(balanceKey) ?? START_BALANCE);
const saveBalance = (value: number) => { localStorage.setItem(balanceKey, String(value)); return value; };

function demoConfig(theme: Theme): GameConfig {
  const engine = ENGINE_CONFIG[theme];
  const ladder = buildLadder(engine);
  return {
    gameId: 'balloon', gameName: 'Воздушный шар', theme, levels: engine.levels,
    betOptions: [10, 20, 50, 100].map((amount, i) => ({ id: `${theme.toLowerCase()}-x${i + 1}`, amount, boosterMultiplier: i + 1 })),
    pointsPerLevel: POINTS_PER_LEVEL, pointsCashoutBonus: POINTS_CASHOUT_BONUS, pointsBoosterBonus: POINTS_BOOSTER_BONUS,
    ladder, levelTable: ladder.slice(1).map((multiplier, i) => ({ level: i + 1, multiplier, popChance: Math.round(hazardAt(engine, i + 1) * 1000) / 1000 })),
    maxMultiplier: ladder[engine.levels], rtp: engine.rtp, version: 1
  };
}

function progress(round: DemoRound, level: number) {
  round.level = level;
  round.multiplier = round.ladder[level];
  round.nextMultiplier = round.ladder[Math.min(level + 1, round.levels)];
  round.boosterActivated = level >= round.boosterLevel;
  round.points = POINTS_PER_LEVEL * level + (round.boosterActivated ? Math.round(POINTS_BOOSTER_BONUS * round.boosterMultiplier) : 0);
  round.potentialPayout = Math.round(round.betAmount * round.multiplier);
}

function settle(round: DemoRound, state: 'CASHED_OUT' | 'SETTLED') {
  round.cashedOutMultiplier = round.multiplier;
  round.payout = Math.max(1, Math.round(round.betAmount * round.multiplier));
  round.points += POINTS_CASHOUT_BONUS;
  round.profit = round.payout - round.betAmount;
  round.state = state;
  round.endedAt = new Date().toISOString();
  round.balance = saveBalance(loadBalance() + round.payout);
  round.balanceAfter = round.balance;
}

function crash(round: DemoRound) {
  round.state = 'CRASHED';
  round.endedAt = new Date().toISOString();
  round.payout = 0;
  round.profit = -round.betAmount;
  round.balance = loadBalance();
  round.balanceAfter = round.balance;
}

/** Догоняет состояние раунда до текущего момента; вызывается на каждом опросе и начисляет выплату один раз. */
function advance(round: DemoRound): DemoRound {
  if (round.state !== 'STARTED') return round;
  const reached = Math.min(round.levels, Math.max(0, Math.floor((Date.now() - round.startedAtMs) / LEVEL_DURATION_MS)));
  if (reached >= round.popLevel) {
    progress(round, round.popLevel - 1);
    crash(round);
    return round;
  }
  progress(round, reached);
  if (reached >= round.levels) settle(round, 'SETTLED');
  return round;
}

function demoRound(id: string): DemoRound {
  const rows = loadRounds();
  const found = rows.find((row) => row.roundId === id);
  if (!found) throw new Error('Раунд не найден');
  const before = found.state;
  advance(found);
  if (before !== found.state) saveRounds(rows);
  return found;
}

class ApiError extends Error {
  constructor(public status: number, message: string) { super(message); }
}

/** Ошибка бизнес-правила от сервера: её нужно показать игроку, а не уходить в демо-режим. */
const isBusinessError = (error: unknown): error is ApiError => error instanceof ApiError && error.status >= 400 && error.status < 500;

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const token = localStorage.getItem('balloon-token');
  const response = await fetch(`${API}${path}`, { ...init, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(init?.headers || {}) } });
  if (!response.ok) {
    const text = await response.text();
    let message = `HTTP ${response.status}`;
    try {
      const body = JSON.parse(text);
      message = body.error || body.detail || message;
    } catch { /* сервер ответил не JSON — оставляем код статуса */ }
    throw new ApiError(response.status, message);
  }
  return response.json();
}

export const api = {
  login: async (username: string, password: string) => { const result = await request<{token:string; user:Me}>('/auth/login', { method:'POST', body: JSON.stringify({username,password}) }); localStorage.setItem('balloon-token', result.token); window.dispatchEvent(new Event('balloon-auth-changed')); return result.user; },
  register: async (username: string, password: string) => { const result = await request<Me>('/auth/register', { method:'POST', body: JSON.stringify({username,password}) }); return api.login(username,password).then(() => result); },
  logout: async () => { try { await request('/auth/logout', { method:'POST' }); } finally { localStorage.removeItem('balloon-token'); window.dispatchEvent(new Event('balloon-auth-changed')); } },
  me: async (): Promise<Me> => {
    try { return await request<Me>('/me'); } catch (error) {
      if (error instanceof ApiError && (error.status === 401 || error.status === 403)) {
        localStorage.removeItem('balloon-token');
        window.dispatchEvent(new Event('balloon-auth-changed'));
        throw error;
      }
      return { username: 'Гость', balance: loadBalance(), role: 'PLAYER' };
    }
  },
  config: async (theme: Theme): Promise<GameConfig> => {
    try { return await request<GameConfig>(`/game/config?theme=${theme}`); } catch { return demoConfig(theme); }
  },
  history: async (): Promise<Round[]> => {
    try { return await request<Round[]>('/rounds/history?limit=50'); } catch {
      const rows = loadRounds();
      rows.forEach(advance);
      saveRounds(rows);
      return rows.filter((row) => row.state !== 'STARTED').reverse();
    }
  },
  leaderboard: async (): Promise<LeaderRow[]> => { const result = await request<{items: LeaderRow[]}>('/leaderboard?limit=100'); return result.items || []; },
  adminUsers: async (): Promise<AdminUser[]> => request<AdminUser[]>('/admin/users'),
  adminAudit: async (): Promise<Array<{actor:string; action:string; details:string; createdAt:string}>> => request('/admin/audit'),
  adminBlock: async (id:string, blocked:boolean, reason:string) => request<AdminUser>(`/admin/users/${id}/${blocked ? 'block' : 'unblock'}`, {method:'POST', body:JSON.stringify({reason})}),
  adminAdjust: async (id:string, balanceDelta:number, pointsDelta:number, reason:string) => request<AdminUser>(`/admin/users/${id}/adjust`, {method:'POST', body:JSON.stringify({balanceDelta,pointsDelta,reason})}),
  rules: async () => {
    try { return await request<{ title: string; sections: { heading: string; text: string }[] }>('/rules'); } catch {
      return { title: 'Правила игры', sections: [
        { heading: 'Сделайте ставку', text: 'Выберите красную или зелёную тему и один из четырёх бустеров.' },
        { heading: 'Рост коэффициентов', text: 'Каждый уровень поднимает коэффициент экспоненциально: чем выше шар, тем дороже следующий шаг.' },
        { heading: 'Взрыв шара', text: 'На каждом уровне шар проходит проверку на разрыв, и шанс взрыва растёт с высотой.' },
        { heading: 'Заберите выигрыш', text: 'Нажмите «Забрать выигрыш» после первого уровня. На последнем уровне выплата начисляется автоматически.' },
        { heading: 'Честная игра', text: 'Результат раунда формируется сервером и подтверждается хешем commitment.' }
      ] };
    }
  },
  start: async (theme: Theme, betOptionId: string): Promise<Round> => {
    try {
      return await request<Round>('/rounds', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ theme, betOptionId }) });
    } catch (error) {
      if (isBusinessError(error)) throw error;
      const config = demoConfig(theme);
      const option = config.betOptions.find((x) => x.id === betOptionId) || config.betOptions[0];
      if (loadBalance() < option.amount) throw new Error('Недостаточно баланса');
      const engine = ENGINE_CONFIG[theme];
      const id = `demo-${Date.now()}`;
      const balance = saveBalance(loadBalance() - option.amount);
      const round: DemoRound = {
        roundId: id, state: 'STARTED', theme, betAmount: option.amount, boosterMultiplier: option.boosterMultiplier,
        boosterLevel: 1 + Math.floor(Math.random() * engine.levels), boosterActivated: false,
        levels: engine.levels, level: 0, multiplier: 1, nextMultiplier: config.ladder?.[1], ladder: config.ladder || [1],
        potentialPayout: option.amount, payout: 0, points: 0, balance, balanceAfter: balance,
        popLevel: rollPopLevel(engine), commitment: `demo-sha256-${id.slice(-6)}`,
        createdAt: new Date().toISOString(), startedAtMs: Date.now()
      };
      saveRounds([...loadRounds(), round]);
      return round;
    }
  },
  round: async (id: string): Promise<Round> => {
    try { return await request<Round>(`/rounds/${id}`); } catch { return demoRound(id); }
  },
  cashout: async (id: string): Promise<Round> => {
    try { return await request<Round>(`/rounds/${id}/cashout`, { method: 'POST' }); } catch (error) {
      if (isBusinessError(error)) throw error;
      const rows = loadRounds();
      const round = rows.find((row) => row.roundId === id);
      if (!round) throw new Error('Раунд не найден');
      advance(round);
      if (round.state !== 'STARTED') throw new Error('Раунд уже завершён');
      if (round.level < 1) throw new Error('Забрать выигрыш можно после первого уровня');
      settle(round, 'CASHED_OUT');
      saveRounds(rows);
      return round;
    }
  },
  proof: async (id: string) => request<Record<string, unknown>>(`/rounds/${id}/proof`),
  adminConfig: async (theme: Theme = 'RED'): Promise<GameConfig> => {
    try { return await request<GameConfig>(`/admin/config?theme=${theme}`); } catch { return demoConfig(theme); }
  },
  updateConfig: async (config: GameConfig): Promise<GameConfig> => {
    try { return await request<GameConfig>(`/admin/config?theme=${config.theme}`, { method: 'PUT', body: JSON.stringify(config) }); }
    catch { return { ...config, version: (config.version || 1) + 1 }; }
  }
};
