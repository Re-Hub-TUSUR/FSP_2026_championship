export type Theme = 'RED' | 'GREEN';
export interface Me { username: string; balance: number; role: string }
export interface BetOption { id: string; amount: number; boosterMultiplier: number }
export interface GameConfig { gameId: string; gameName?: string; theme: Theme; levels: number; betOptions: BetOption[]; pointsPerLevel: number; pointsCashoutBonus?: number; pointsBoosterBonus?: number; version?: number }
export interface Round { roundId: string; state: string; theme: Theme; betAmount: number; boosterMultiplier: number; levels: number; level: number; multiplier: number; cashedOutMultiplier?: number; payout?: number; points: number; commitment?: string; reward?: string; createdAt?: string; serverTime?: string }

const API = (import.meta.env.VITE_API_URL as string | undefined) || '/api/v1';
const demoKey = 'balloon-demo-rounds';
const balanceKey = 'balloon-demo-balance';
const demoRounds = (): (Round & { crashAt?: number })[] => JSON.parse(localStorage.getItem(demoKey) || '[]');
const saveRounds = (rows: (Round & { crashAt?: number })[]) => localStorage.setItem(demoKey, JSON.stringify(rows.slice(-40)));
const demoBalance = () => Number(localStorage.getItem(balanceKey) || 1000);
const saveBalance = (value: number) => localStorage.setItem(balanceKey, String(value));
const demoConfig = (theme: Theme): GameConfig => ({ gameId: 'balloon', gameName: 'Воздушный шар', theme, levels: theme === 'RED' ? 12 : 9, betOptions: [10, 20, 50, 100].map((amount, i) => ({ id: `${theme.toLowerCase()}-x${i + 1}`, amount, boosterMultiplier: i + 1 })), pointsPerLevel: 10, pointsCashoutBonus: 20, pointsBoosterBonus: 15, version: 1 });

async function request<T>(path: string, init?: RequestInit): Promise<T> { const r = await fetch(`${API}${path}`, { headers: { 'Content-Type': 'application/json', ...(init?.headers || {}) }, ...init }); if (!r.ok) throw new Error(await r.text()); return r.json(); }
function demoRound(id: string): Round {
  const rows = demoRounds(); const found = rows.find((x) => x.roundId === id); if (!found) throw new Error('Раунд не найден');
  if (found.state === 'STARTED') { const elapsed = (Date.now() - Date.parse(found.createdAt || new Date().toISOString())) / 1000; const level = Math.min(found.levels, Math.floor(elapsed * 1.5)); const multiplier = Math.min(found.crashAt ?? 4.2, 1 + level * .26); if (multiplier >= (found.crashAt ?? 4.2) || level >= found.levels) { found.state = 'SETTLED'; found.level = found.levels; found.multiplier = found.crashAt ?? 4.2; } else { found.level = level; found.multiplier = multiplier; } saveRounds(rows); }
  return found;
}
export const api = {
  me: async (): Promise<Me> => { try { return await request<Me>('/me'); } catch { return { username: 'Гость', balance: demoBalance(), role: 'PLAYER' }; } },
  config: async (theme: Theme): Promise<GameConfig> => { try { return await request<GameConfig>(`/game/config?theme=${theme}`); } catch { return demoConfig(theme); } },
  history: async (): Promise<Round[]> => { try { return await request<Round[]>('/rounds/history?limit=50'); } catch { return demoRounds().filter((r) => r.state !== 'STARTED').reverse(); } },
  rules: async () => { try { return await request<{ title: string; sections: { heading: string; text: string }[] }>('/rules'); } catch { return { title: 'Правила игры', sections: [{ heading: 'Сделайте ставку', text: 'Выберите красную или зелёную тему и один из четырёх бустеров.' }, { heading: 'Наблюдайте за полётом', text: 'Коэффициент растёт с каждым уровнем. Чем дольше полёт, тем выше потенциальная выплата.' }, { heading: 'Заберите выигрыш', text: 'Нажмите «Забрать выигрыш» после первого уровня до того, как шар лопнет.' }, { heading: 'Честная игра', text: 'Результат раунда формируется сервером и подтверждается хешем commitment.' }] }; } },
  start: async (theme: Theme, betOptionId: string): Promise<Round> => { try { return await request<Round>('/rounds', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ theme, betOptionId }) }); } catch { const cfg = demoConfig(theme); const option = cfg.betOptions.find((x) => x.id === betOptionId) || cfg.betOptions[0]; const id = `demo-${Date.now()}`; const round: Round & { crashAt: number } = { roundId: id, state: 'STARTED', theme, betAmount: option.amount, boosterMultiplier: option.boosterMultiplier, levels: cfg.levels, level: 0, multiplier: 1, points: 0, commitment: 'demo-sha256-' + id.slice(-6), createdAt: new Date().toISOString(), crashAt: 3.2 + Math.random() * 2.2 }; saveBalance(Math.max(0, demoBalance() - option.amount)); saveRounds([...demoRounds(), round]); return round; } },
  round: async (id: string): Promise<Round> => { try { return await request<Round>(`/rounds/${id}`); } catch { return demoRound(id); } },
  cashout: async (id: string): Promise<Round> => { try { return await request<Round>(`/rounds/${id}/cashout`, { method: 'POST' }); } catch { const round = demoRound(id); if (round.state !== 'STARTED' || round.level < 1) throw new Error('Забрать выигрыш можно после первого уровня'); round.state = 'CASHED_OUT'; round.cashedOutMultiplier = round.multiplier; round.payout = Math.round(round.betAmount * round.multiplier); round.points += 20; saveBalance(demoBalance() + round.payout); saveRounds(demoRounds().map((x) => x.roundId === id ? round : x)); return round; } },
  adminConfig: async (theme: Theme = 'RED'): Promise<GameConfig> => { try { return await request<GameConfig>(`/admin/config?theme=${theme}`); } catch { return demoConfig(theme); } },
  updateConfig: async (cfg: GameConfig): Promise<GameConfig> => {
    try {
      return await request<GameConfig>('/admin/config?theme=' + cfg.theme, {
        method: 'PUT',
        body: JSON.stringify(cfg)
      });
    } catch {
      return { ...cfg, version: (cfg.version || 1) + 1 };
    }
  }
};
