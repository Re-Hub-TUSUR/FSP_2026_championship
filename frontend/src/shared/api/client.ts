import { ENGINE_CONFIG, FIRST_BOOSTER_LEVEL, MIN_CASHOUT_LEVEL, boosterAppearChance, buildLadder, hazardAt, ladderForBooster, rollPopLevel } from '../game/engine';

export type Theme = 'RED' | 'GREEN';
export interface Me { id?: string; username: string; balance: number; role: string; points?: number; level?: number; blocked?: boolean; puzzleMask?: number; puzzleFragments?: number; puzzleBonus?: number; puzzlesCompleted?: number; dailyBonus?: DailyBonusState; lotteryTickets?: number; wins?: number }
export interface PuzzleAward { fragment: number; collected: number; total: number; completed: boolean; bonus: number; ticketEligible?: boolean }
/** Состояние пазла и лотерейного билета за текущий календарный день. */
export interface DailyBonusState {
  day: string;
  collected: number;
  total: number;
  eligible: boolean;
  ticketAvailable: boolean;
  claimed: boolean;
  reward: number | null;
  /** Optional server fields retained for richer UI and forward compatibility. */
  puzzleMask?: number;
  ticketState?: 'LOCKED' | 'AVAILABLE' | 'SCRATCHED' | string;
  balance?: number;
  balanceAfter?: number;
  nextResetAt?: string;
}
/** Апсейл «Закрепи успех»: каталог наборов и персональное предложение после раунда. */
export interface TicketPack { id: string; tickets: number; price: number; label: string }
export interface ScratchPrize { amount: number; chance: number }
export interface LotteryCatalog { tickets: number; balance: number; ticketPrice: number; packs: TicketPack[]; scratchPrizes?: ScratchPrize[]; scratchExpected?: number }
export interface ScratchResult { prize: number; tickets: number; balance: number }
export interface LotteryOffer { eligible: boolean; reason?: string; tickets: number; price: number; fullPrice: number; payout: number; timeoutSeconds: number }
export interface LeaderRow { id: string; username: string; balance: number; rank?: number; level: number; points: number; multiplier: number; trend: number; rounds?: number; cashouts?: number; updatedAt?: string }
export interface Tournament { startedAt: string; endsAt: string; active: boolean; firstPrize: number; secondPrize: number; thirdPrize: number; prizesPaid: boolean }
export interface RoundProof { roundId: string; commitment: string; configVersion: number; seed?: string; popLevel?: number; boosterLevel?: number; ladder?: number[]; crashMultiplier?: number; formula?: string }
export interface AdminUser { id: string; username: string; role: string; blocked: boolean; balance: number; points: number; level: number; createdAt?: string }
export interface BetOption { id: string; amount: number; boosterMultiplier: number; boosterChance?: number; ladder?: number[] }
export interface LevelRow { level: number; multiplier: number; popChance: number; boosterChance?: number; expectedReturn?: number }
export interface CrashRow { roundId: string; theme: Theme; crashMultiplier: number; survived: boolean; endedAt?: string }
export interface GameConfig {
  gameId: string; gameName?: string; gameType?: string; active?: boolean;
  theme: Theme; levels: number; betOptions: BetOption[];
  pointsPerLevel: number; pointsCashoutBonus?: number; pointsBoosterBonus?: number;
  baseHazard?: number; hazardGrowth?: number; maxHazard?: number; rtp?: number;
  boosterWeights?: number[]; ladder?: number[]; ladderOverride?: number[]; ladderSource?: 'formula' | 'custom';
  levelTable?: LevelRow[]; maxMultiplier?: number; minWinAmount?: number; popupTimeout?: number; version?: number;
}
export interface Round {
  roundId: string; state: string; theme: Theme; betAmount: number; boosterMultiplier: number;
  boosterLevel?: number; boosterActivated?: boolean; levels: number; level: number; multiplier: number;
  nextMultiplier?: number; ladder?: number[]; potentialPayout?: number; cashedOutMultiplier?: number;
  payout?: number; profit?: number; points: number; balance?: number; balanceAfter?: number;
  popLevel?: number; crashMultiplier?: number; potentialMax?: number; flightOver?: boolean; cashedOut?: boolean; reward?: PuzzleAward;
  commitment?: string; createdAt?: string; endedAt?: string; serverTime?: string;
}

const API = (import.meta.env.VITE_API_URL as string | undefined) || '/api/v1';

/**
 * Local game data is opt-in only.  A failed production request must remain
 * visible to the caller instead of being replaced with a local transaction.
 */
export const isDemoMode = (): boolean => import.meta.env.VITE_DEMO_MODE === 'true';

const POINTS_PER_LEVEL = 10;
const POINTS_CASHOUT_BONUS = 20;
const POINTS_BOOSTER_BONUS = 15;
const START_BALANCE = 1000;
const LEVEL_DURATION_MS = 1500;

const roundsKey = 'balloon-demo-rounds';
const balanceKey = 'balloon-demo-balance';
const puzzleKey = 'balloon-demo-puzzle';
const dailyBonusKey = 'balloon-demo-daily-bonus';
const PUZZLE_FRAGMENTS = 6;
/** Награда за ежедневный билет в демо-режиме — как на сервере: от 10 до 100 бонусов. */
const DAILY_REWARD_MIN = 10;
const DAILY_REWARD_MAX = 100;

/**
 * Generates an idempotency key in browsers where `crypto.randomUUID` is not
 * available (for example, an HTTP deployment or an older WebView).  The
 * fallback still uses Web Crypto when available and keeps the UUID v4 shape
 * expected by tooling and logs.
 */
const createIdempotencyKey = (): string => {
  const webCrypto = globalThis.crypto;
  if (typeof webCrypto?.randomUUID === 'function') {
    try { return webCrypto.randomUUID(); } catch { /* fall through to getRandomValues */ }
  }

  const bytes = new Uint8Array(16);
  try {
    if (typeof webCrypto?.getRandomValues === 'function') {
      webCrypto.getRandomValues(bytes);
    } else {
      throw new Error('Web Crypto is unavailable');
    }
  } catch {
    // Idempotency keys are not secrets; preserve functionality in legacy
    // browsers that expose neither randomUUID nor getRandomValues.
    for (let index = 0; index < bytes.length; index += 1) bytes[index] = Math.floor(Math.random() * 256);
  }

  // RFC 4122 version 4 and variant bits.
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
};

type DemoRound = Round & { popLevel: number; ladder: number[]; boosterLevel: number; startedAtMs: number };

const loadRounds = (): DemoRound[] => { try { return JSON.parse(localStorage.getItem(roundsKey) || '[]'); } catch { return []; } };
const saveRounds = (rows: DemoRound[]) => localStorage.setItem(roundsKey, JSON.stringify(rows.slice(-50)));
const loadBalance = () => Number(localStorage.getItem(balanceKey) ?? START_BALANCE);
const saveBalance = (value: number) => { localStorage.setItem(balanceKey, String(value)); return value; };

const isRecord = (value: unknown): value is Record<string, unknown> => Boolean(value) && typeof value === 'object' && !Array.isArray(value);
const numberField = (value: Record<string, unknown>, ...keys: string[]) => {
  for (const key of keys) {
    const candidate = value[key];
    if (typeof candidate === 'number' && Number.isFinite(candidate)) return candidate;
    if (typeof candidate === 'string' && candidate.trim() !== '' && Number.isFinite(Number(candidate))) return Number(candidate);
  }
  return undefined;
};
const booleanField = (value: Record<string, unknown>, ...keys: string[]) => {
  for (const key of keys) {
    const candidate = value[key];
    if (typeof candidate === 'boolean') return candidate;
    if (candidate === 'true' || candidate === '1') return true;
    if (candidate === 'false' || candidate === '0') return false;
  }
  return undefined;
};

const localDay = () => {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
};
const nextLocalMidnight = () => {
  const next = new Date();
  next.setHours(24, 0, 0, 0);
  return next.toISOString();
};

/**
 * Приводит ответы разных версий API к одному клиентскому контракту. Серверная
 * версия может вернуть состояние как напрямую, так и внутри `dailyBonus`.
 */
export function normalizeDailyBonus(value: unknown, fallback?: Partial<DailyBonusState>): DailyBonusState | null {
  const source = isRecord(value) && isRecord(value.dailyBonus) ? value.dailyBonus : value;
  if (!isRecord(source)) return null;
  const ticketObject = isRecord(source.ticket) ? source.ticket : undefined;
  const ticketStateValue = source.ticketState ?? ticketObject?.state;
  const ticketState = typeof ticketStateValue === 'string' ? ticketStateValue.toUpperCase() : undefined;
  const mask = numberField(source, 'puzzleMask', 'mask');
  const total = Math.max(1, Math.round(numberField(source, 'total', 'required', 'puzzleFragments') ?? fallback?.total ?? PUZZLE_FRAGMENTS));
  const collectedFromMask = mask === undefined ? undefined : Math.max(0, Math.min(total, bitCount(Math.round(mask))));
  const progressValue = numberField(source, 'collected', 'progress', 'collectedCount', 'puzzleCollected');
  const collected = Math.max(0, Math.min(total, Math.round(progressValue ?? collectedFromMask ?? fallback?.collected ?? 0)));
  const rewardValue = numberField(source, 'reward', 'rewardAmount', 'bonus', 'amount') ?? (ticketObject ? numberField(ticketObject, 'reward', 'rewardAmount', 'bonus', 'amount') : undefined);
  const stateImpliesClaimed = ticketState === 'SCRATCHED' || ticketState === 'CLAIMED';
  const claimed = booleanField(source, 'claimed', 'ticketClaimed', 'scratched', 'used')
    ?? (stateImpliesClaimed ? true : fallback?.claimed ?? false);
  const eligible = booleanField(source, 'eligible', 'canClaim', 'ready', 'completed') ?? (collected >= total);
  const ticketAvailable = booleanField(source, 'ticketAvailable', 'hasTicket')
    ?? (ticketState === 'AVAILABLE' || ticketState === 'READY' ? true : undefined)
    ?? (eligible && !claimed);
  const dayValue = source.day ?? source.date ?? source.dayKey ?? source.period;
  const day = typeof dayValue === 'string' && dayValue.trim() ? dayValue : (fallback?.day ?? localDay());
  const reset = source.nextResetAt ?? source.resetAt ?? source.expiresAt;
  const currentBalance = numberField(source, 'balance') ?? (ticketObject ? numberField(ticketObject, 'balance') : undefined);
  const balanceAfter = numberField(source, 'balanceAfter') ?? (ticketObject ? numberField(ticketObject, 'balanceAfter') : undefined);
  return {
    day,
    collected,
    total,
    eligible,
    ticketAvailable: ticketAvailable && !claimed,
    claimed,
    reward: rewardValue === undefined ? (fallback?.reward ?? null) : Math.max(0, Math.round(rewardValue)),
    ...(mask === undefined ? {} : { puzzleMask: Math.round(mask) }),
    ...(ticketState ? { ticketState } : {}),
    ...(currentBalance === undefined ? {} : { balance: currentBalance }),
    ...(balanceAfter === undefined ? {} : { balanceAfter }),
    ...(typeof reset === 'string' ? { nextResetAt: reset } : fallback?.nextResetAt ? { nextResetAt: fallback.nextResetAt } : {})
  };
}

const bitCount = (value: number) => {
  let n = value >>> 0;
  let count = 0;
  while (n) { n &= n - 1; count += 1; }
  return count;
};

const demoDailyState = (): DailyBonusState => {
  const today = localDay();
  let saved: Partial<DailyBonusState> = {};
  try { saved = JSON.parse(localStorage.getItem(dailyBonusKey) || '{}') as Partial<DailyBonusState>; } catch { saved = {}; }
  if (saved.day !== today) {
    saved = { day: today, collected: 0, total: PUZZLE_FRAGMENTS, eligible: false, ticketAvailable: false, claimed: false, reward: null, puzzleMask: 0 };
    // The daily collection starts from zero at the local calendar boundary.
    localStorage.setItem(puzzleKey, '0');
  }
  const total = Math.max(1, saved.total ?? PUZZLE_FRAGMENTS);
  const collected = Math.max(0, Math.min(total, saved.collected ?? 0));
  const eligible = Boolean(saved.eligible) || collected >= total;
  const claimed = Boolean(saved.claimed);
  const state: DailyBonusState = {
    day: today,
    collected,
    total,
    eligible,
    ticketAvailable: eligible && !claimed,
    claimed,
    reward: typeof saved.reward === 'number' ? saved.reward : null,
    puzzleMask: typeof saved.puzzleMask === 'number' ? saved.puzzleMask : Number(localStorage.getItem(puzzleKey) || 0),
    nextResetAt: nextLocalMidnight()
  };
  localStorage.setItem(dailyBonusKey, JSON.stringify(state));
  return state;
};
const saveDemoDaily = (state: DailyBonusState) => { localStorage.setItem(dailyBonusKey, JSON.stringify(state)); return state; };

/** Локальный fallback для запуска frontend без backend. */
function demoDailyClaim(): DailyBonusState {
  const state = demoDailyState();
  if (!state.eligible) throw new Error('Сначала соберите все фрагменты пазла');
  if (state.claimed) return state;
  const reward = DAILY_REWARD_MIN + Math.floor(Math.random() * (DAILY_REWARD_MAX - DAILY_REWARD_MIN + 1));
  saveBalance(loadBalance() + reward);
  return saveDemoDaily({ ...state, claimed: true, ticketAvailable: false, reward });
}

/** Демо-версия награды: случайный недостающий фрагмент и билет после полного пазла. */
function awardFragment(): PuzzleAward {
  const daily = demoDailyState();
  let mask = daily.puzzleMask ?? Number(localStorage.getItem(puzzleKey) || 0);
  const missing = Array.from({ length: PUZZLE_FRAGMENTS }, (_, i) => i).filter((i) => (mask & (1 << i)) === 0);
  if (missing.length === 0) return { fragment: PUZZLE_FRAGMENTS - 1, collected: PUZZLE_FRAGMENTS, total: PUZZLE_FRAGMENTS, completed: true, bonus: 0, ticketEligible: true };
  const fragment = missing[Math.floor(Math.random() * missing.length)];
  mask |= 1 << fragment;
  const collected = Array.from({ length: PUZZLE_FRAGMENTS }, (_, i) => (mask & (1 << i)) !== 0).filter(Boolean).length;
  const completed = collected === PUZZLE_FRAGMENTS;
  localStorage.setItem(puzzleKey, String(mask));
  saveDemoDaily({
    ...daily,
    collected,
    total: PUZZLE_FRAGMENTS,
    puzzleMask: mask,
    eligible: completed || daily.eligible,
    ticketAvailable: completed || daily.ticketAvailable,
    claimed: daily.claimed,
    reward: daily.reward
  });
  return { fragment, collected, total: PUZZLE_FRAGMENTS, completed, bonus: 0, ticketEligible: completed };
}

function demoConfig(theme: Theme): GameConfig {
  const engine = ENGINE_CONFIG[theme];
  const ladder = buildLadder(engine);
  return {
    gameId: 'balloon', gameName: 'Воздушный шар', theme, levels: engine.levels,
    betOptions: [10, 20, 50, 100].map((amount, i) => ({
      id: `${theme.toLowerCase()}-x${i + 1}`,
      amount,
      boosterMultiplier: i + 1,
      boosterChance: boosterAppearChance(i + 1),
      ladder: ladderForBooster(engine, i + 1).slice(1)
    })),
    pointsPerLevel: POINTS_PER_LEVEL, pointsCashoutBonus: POINTS_CASHOUT_BONUS, pointsBoosterBonus: POINTS_BOOSTER_BONUS,
    ladder, levelTable: ladder.slice(1).map((multiplier, i) => ({ level: i + 1, multiplier, popChance: Math.round(hazardAt(engine, i + 1) * 1000) / 1000 })),
    maxMultiplier: ladder[engine.levels], rtp: engine.rtp, version: 1
  };
}

const isCashedOut = (round: DemoRound) => round.cashedOutMultiplier != null;

const hasBooster = (round: DemoRound) => round.boosterLevel > 0 && round.boosterMultiplier > 1;

const multiplierAt = (round: DemoRound, level: number) => {
  const base = round.ladder[Math.max(0, Math.min(level, round.levels))];
  return round.boosterActivated && level >= round.boosterLevel ? Math.round(base * round.boosterMultiplier * 100) / 100 : base;
};

function progress(round: DemoRound, level: number) {
  // После cashout бустер не срабатывает и очки не начисляются — шар летит только для наглядности.
  if (!isCashedOut(round) && hasBooster(round) && level >= round.boosterLevel) round.boosterActivated = true;
  round.level = level;
  round.multiplier = multiplierAt(round, level);
  round.nextMultiplier = multiplierAt(round, Math.min(level + 1, round.levels));
  if (!isCashedOut(round)) {
    round.points = POINTS_PER_LEVEL * level + (round.boosterActivated ? Math.round(POINTS_BOOSTER_BONUS * round.boosterMultiplier) : 0);
  }
  round.potentialPayout = Math.round(round.betAmount * round.multiplier);
}

function settle(round: DemoRound, state: 'CASHED_OUT' | 'SETTLED') {
  round.cashedOutMultiplier = round.multiplier;
  round.payout = Math.max(1, Math.round(round.betAmount * round.multiplier));
  round.points += POINTS_CASHOUT_BONUS;
  round.profit = round.payout - round.betAmount;
  round.state = state;
  round.balance = saveBalance(loadBalance() + round.payout);
  round.balanceAfter = round.balance;
  if (state === 'SETTLED') endFlight(round);
}

/** Шар лопнул или долетел до вершины: полёт закончен, результат можно показывать. */
function endFlight(round: DemoRound) {
  round.flightOver = true;
  round.endedAt = new Date().toISOString();
  if (!isCashedOut(round)) {
    round.state = 'CRASHED';
    round.payout = 0;
    round.profit = -round.betAmount;
  }
  round.reward = awardFragment();
  round.crashMultiplier = round.ladder[Math.min(round.popLevel, round.levels)];
  const best = Math.min(round.popLevel - 1, round.levels);
  round.potentialMax = best < MIN_CASHOUT_LEVEL ? 0 : Math.max(1, Math.round(round.betAmount * (hasBooster(round) && best >= round.boosterLevel ? round.ladder[best] * round.boosterMultiplier : round.ladder[best])));
  round.balance = loadBalance();
  round.balanceAfter = round.balance;
}

/** Догоняет состояние раунда до текущего момента; вызывается на каждом опросе и начисляет выплату один раз. */
function advance(round: DemoRound): DemoRound {
  if (round.flightOver) return round;
  const reached = Math.min(round.levels, Math.max(0, Math.floor((Date.now() - round.startedAtMs) / LEVEL_DURATION_MS)));
  if (reached >= round.popLevel) {
    progress(round, round.popLevel - 1);
    endFlight(round);
    return round;
  }
  progress(round, reached);
  if (reached >= round.levels && !isCashedOut(round)) settle(round, 'SETTLED');
  else if (reached >= round.levels) endFlight(round);
  return round;
}

function demoRound(id: string): DemoRound {
  const rows = loadRounds();
  const found = rows.find((row) => row.roundId === id);
  if (!found) throw new Error('Раунд не найден');
  const before = `${found.state}:${found.flightOver}:${found.level}`;
  advance(found);
  if (before !== `${found.state}:${found.flightOver}:${found.level}`) saveRounds(rows);
  return found;
}

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
    this.name = 'ApiError';
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const token = localStorage.getItem('balloon-token');
  const response = await fetch(`${API}${path}`, { ...init, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(init?.headers || {}) } });
  if (!response.ok) {
    const text = await response.text();
    // 502/503/504 приходят от nginx, пока backend ещё поднимается — показываем человеческий текст вместо кода.
    let message = response.status >= 502 && response.status <= 504
      ? 'Сервер ещё запускается, попробуйте через полминуты'
      : `Ошибка сервера (${response.status})`;
    try {
      const body = JSON.parse(text) as Record<string, unknown>;
      const serverMessage = body.error ?? body.detail ?? body.message;
      if (typeof serverMessage === 'string' && serverMessage.trim()) message = serverMessage;
    } catch { /* сервер ответил не JSON — оставляем понятный текст */ }
    throw new ApiError(response.status, message);
  }
  return response.json();
}

export const api = {
  login: async (username: string, password: string) => { const result = await request<{token:string; user:Me}>('/auth/login', { method:'POST', body: JSON.stringify({username,password}) }); sessionStorage.removeItem('balloon-upsell-shown'); localStorage.setItem('balloon-token', result.token); window.dispatchEvent(new Event('balloon-auth-changed')); return result.user; },
  register: async (username: string, password: string) => { const result = await request<Me>('/auth/register', { method:'POST', body: JSON.stringify({username,password}) }); return api.login(username,password).then(() => result); },
  logout: async () => { try { await request('/auth/logout', { method:'POST' }); } finally { localStorage.removeItem('balloon-token'); sessionStorage.removeItem('balloon-upsell-shown'); window.dispatchEvent(new Event('balloon-auth-changed')); } },
  topUp: async (): Promise<Me> => request<Me>('/auth/topup', { method: 'POST' }),
  me: async (): Promise<Me> => {
    try {
      const profile = await request<Me>('/me');
      const daily = normalizeDailyBonus(profile);
      return daily ? { ...profile, dailyBonus: daily, puzzleMask: daily.puzzleMask ?? profile.puzzleMask, puzzleFragments: daily.total } : profile;
    } catch (error) {
      if (!isDemoMode()) {
        if (error instanceof ApiError && (error.status === 401 || error.status === 403)) {
          localStorage.removeItem('balloon-token');
          sessionStorage.removeItem('balloon-upsell-shown');
          window.dispatchEvent(new Event('balloon-auth-changed'));
        }
        throw error;
      }
      const localToken = localStorage.getItem('balloon-token');
      return {
        username: localToken === 'demo-admin' ? 'admin' : localToken === 'demo-demo' ? 'demo' : 'Гость', balance: loadBalance(), role: localToken === 'demo-admin' ? 'ADMIN' : 'PLAYER',
        puzzleMask: Number(localStorage.getItem(puzzleKey) || 0),
        puzzleFragments: PUZZLE_FRAGMENTS, puzzleBonus: 0, dailyBonus: demoDailyState()
      };
    }
  },
  dailyBonus: async (): Promise<DailyBonusState> => {
    try {
      const result = await request<unknown>('/daily-bonus');
      const state = normalizeDailyBonus(result);
      if (!state) throw new Error('Сервер вернул некорректное состояние ежедневного бонуса');
      return state;
    } catch (error) {
      if (!isDemoMode()) throw error;
      return demoDailyState();
    }
  },
  claimDailyBonus: async (): Promise<DailyBonusState> => {
    const idempotencyKey = createIdempotencyKey();
    const payload: RequestInit = {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify({ idempotencyKey })
    };
    try {
      const result = await request<unknown>('/daily-bonus/claim', payload);
      const state = normalizeDailyBonus(result);
      if (!state) throw new Error('Сервер вернул некорректный выигрыш');
      return state;
    } catch (error) {
      if (error instanceof ApiError && (error.status === 404 || error.status === 405)) {
        try {
          const result = await request<unknown>('/daily-bonus/scratch', payload);
          const state = normalizeDailyBonus(result);
          if (!state) throw new Error('Сервер вернул некорректный выигрыш');
          return state;
        } catch (aliasError) {
          if (!isDemoMode()) throw aliasError;
        }
      }
      if (!isDemoMode()) throw error;
      return demoDailyClaim();
    }
  },
  config: async (theme: Theme): Promise<GameConfig> => {
    try { return await request<GameConfig>(`/game/config?theme=${theme}`); } catch (error) {
      if (!isDemoMode()) throw error;
      return demoConfig(theme);
    }
  },
  history: async (): Promise<Round[]> => {
    try { return await request<Round[]>('/rounds/history?limit=50'); } catch (error) {
      if (!isDemoMode()) throw error;
      const rows = loadRounds();
      rows.forEach(advance);
      saveRounds(rows);
      return rows.filter((row) => row.flightOver).reverse();
    }
  },
  leaderboard: async (): Promise<LeaderRow[]> => { const result = await request<{items: LeaderRow[]}>('/leaderboard?limit=100'); return result.items || []; },
  tournament: async (): Promise<Tournament> => request<Tournament>('/tournament'),
  adminUsers: async (): Promise<AdminUser[]> => request<AdminUser[]>('/admin/users'),
  adminAudit: async (): Promise<Array<{actor:string; action:string; details:string; createdAt:string}>> => request('/admin/audit'),
  adminBlock: async (id:string, blocked:boolean, reason:string) => request<AdminUser>(`/admin/users/${id}/${blocked ? 'block' : 'unblock'}`, {method:'POST', body:JSON.stringify({reason})}),
  adminAdjust: async (id:string, balanceDelta:number, pointsDelta:number, reason:string) => request<AdminUser>(`/admin/users/${id}/adjust`, {method:'POST', body:JSON.stringify({balanceDelta,pointsDelta,reason})}),
  rules: async () => {
    try { return await request<{ title: string; sections: { heading: string; text: string }[] }>('/rules'); } catch (error) {
      if (!isDemoMode()) throw error;
      return { title: 'Правила игры', sections: [
        { heading: 'Сделайте ставку', text: 'Выберите красную или зелёную тему и один из четырёх бустеров.' },
        { heading: 'Рост коэффициентов', text: 'Каждый уровень поднимает коэффициент экспоненциально: чем выше шар, тем дороже следующий шаг.' },
        { heading: 'Взрыв шара', text: 'Первый подъём проходит без риска, а начиная со второго уровня шар проходит проверку на разрыв, и шанс взрыва растёт с высотой.' },
        { heading: 'Заберите выигрыш', text: 'Нажмите «Забрать выигрыш» начиная со второго уровня — коэффициент фиксируется, но шар летит дальше до взрыва. На последнем уровне выплата начисляется автоматически.' },
        { heading: 'Бустеры', text: 'Бустер ждёт на случайном уровне начиная с третьего: долетев до него раньше cashout, вы умножаете коэффициент и получаете дополнительные очки.' },
        { heading: 'Ежедневный билет', text: 'Каждый завершённый раунд даёт недостающий фрагмент дневного пазла. Соберите 6 из 6 и сотрите билет: награда от 10 до 100 бонусов.' },
        { heading: 'Честная игра', text: 'Результат раунда формируется сервером и подтверждается хешем commitment.' }
      ] };
    }
  },
  start: async (theme: Theme, betOptionId: string): Promise<Round> => {
    try {
      return await request<Round>('/rounds', { method: 'POST', headers: { 'Idempotency-Key': createIdempotencyKey() }, body: JSON.stringify({ theme, betOptionId }) });
    } catch (error) {
      if (!isDemoMode()) throw error;
      const config = demoConfig(theme);
      const option = config.betOptions.find((x) => x.id === betOptionId) || config.betOptions[0];
      if (loadBalance() < option.amount) throw new Error('Недостаточно баланса');
      const engine = ENGINE_CONFIG[theme];
      const id = `demo-${Date.now()}`;
      const balance = saveBalance(loadBalance() - option.amount);
      const tierLadder = ladderForBooster(engine, option.boosterMultiplier);
      const round: DemoRound = {
        roundId: id, state: 'STARTED', theme, betAmount: option.amount, boosterMultiplier: option.boosterMultiplier,
        boosterLevel: Math.random() < boosterAppearChance(option.boosterMultiplier) ? FIRST_BOOSTER_LEVEL + Math.floor(Math.random() * (engine.levels - FIRST_BOOSTER_LEVEL + 1)) : 0,
        boosterActivated: false, flightOver: false,
        levels: engine.levels, level: 0, multiplier: 1, nextMultiplier: tierLadder[1], ladder: tierLadder,
        potentialPayout: option.amount, payout: 0, points: 0, balance, balanceAfter: balance,
        popLevel: rollPopLevel(engine), commitment: `demo-sha256-${id.slice(-6)}`,
        createdAt: new Date().toISOString(), startedAtMs: Date.now()
      };
      saveRounds([...loadRounds(), round]);
      return round;
    }
  },
  round: async (id: string): Promise<Round> => {
    try { return await request<Round>(`/rounds/${id}`); } catch (error) {
      if (!isDemoMode()) throw error;
      return demoRound(id);
    }
  },
  cashout: async (id: string): Promise<Round> => {
    try { return await request<Round>(`/rounds/${id}/cashout`, { method: 'POST' }); } catch (error) {
      if (!isDemoMode()) throw error;
      const rows = loadRounds();
      const round = rows.find((row) => row.roundId === id);
      if (!round) throw new Error('Раунд не найден');
      advance(round);
      if (isCashedOut(round)) return round;
      if (round.flightOver) throw new Error('Раунд уже завершён');
      if (round.level < MIN_CASHOUT_LEVEL) throw new Error('Забрать выигрыш можно со второго уровня: первый подъём проходит без риска');
      settle(round, 'CASHED_OUT');
      saveRounds(rows);
      return round;
    }
  },
  crashes: async (): Promise<CrashRow[]> => {
    try { return await request<CrashRow[]>('/rounds/crashes?limit=20'); } catch (error) {
      if (!isDemoMode()) throw error;
      const rows = loadRounds();
      rows.forEach(advance);
      return rows.filter((row) => row.flightOver).reverse().slice(0, 20).map((row) => ({
        roundId: row.roundId,
        theme: row.theme,
        crashMultiplier: row.ladder?.[Math.min(row.popLevel, row.levels)] ?? row.multiplier,
        survived: row.popLevel > row.levels,
        endedAt: row.endedAt
      }));
    }
  },
  proof: async (id: string): Promise<RoundProof> => request<RoundProof>(`/rounds/${id}/proof`),
  myHistory: async (): Promise<Round[]> => {
    try { return await request<Round[]>('/rounds/mine?limit=50'); } catch (error) {
      if (!isDemoMode()) throw error;
      return api.history();
    }
  },
  // Лотерея работает только с авторизованным сервером: демо-режима у покупок нет.
  lottery: (): Promise<LotteryCatalog> => request<LotteryCatalog>('/lottery'),
  buyTickets: (packId: string) => request<{ tickets: number; balance: number }>('/lottery/buy', { method: 'POST', body: JSON.stringify({ packId }) }),
  scratchTicket: () => request<ScratchResult>('/lottery/scratch', { method: 'POST' }),
  lotteryOffer: (roundId: string): Promise<LotteryOffer> => request<LotteryOffer>(`/lottery/offer/${roundId}`),
  acceptOffer: (roundId: string) => request<{ tickets: number; balance: number }>('/lottery/offer/accept', { method: 'POST', body: JSON.stringify({ roundId }) }),
  // У админских запросов нет демо-режима: ошибку сервера должен увидеть администратор, а не подмена локальными данными.
  adminConfig: (theme: Theme = 'RED'): Promise<GameConfig> => request<GameConfig>(`/admin/config?theme=${theme}`),
  resetConfig: (theme: Theme): Promise<GameConfig> => request<GameConfig>(`/admin/config/reset?theme=${theme}`, { method: 'POST' }),
  updateConfig: (config: GameConfig): Promise<GameConfig> =>
    request<GameConfig>(`/admin/config?theme=${config.theme}`, { method: 'PUT', body: JSON.stringify(config) })
};
