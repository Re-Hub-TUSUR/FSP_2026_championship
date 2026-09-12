import { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, type DailyBonusState } from '../../shared/api/client';

type DailyBonusPanelProps = {
  token: string | null;
  /** /me already contains the state; it lets the panel render immediately. */
  profileState?: DailyBonusState;
  /** Render the daily collection as a compact header chip with a popover. */
  compact?: boolean;
};

type ScratchPhase = 'idle' | 'scratching' | 'revealed';

const wait = (ms: number) => new Promise<void>((resolve) => window.setTimeout(resolve, ms));

function rewardLabel(value: number | null | undefined) {
  return value == null ? '' : `+${value.toLocaleString('ru-RU')} ⛃`;
}

function resetLabel(nextResetAt?: string) {
  if (!nextResetAt) return '';
  const remaining = new Date(nextResetAt).getTime() - Date.now();
  if (!Number.isFinite(remaining) || remaining <= 0) return 'обновится скоро';
  const hours = Math.floor(remaining / 3_600_000);
  const minutes = Math.floor((remaining % 3_600_000) / 60_000);
  return `сброс через ${hours}ч ${minutes}м`;
}

function countdownLabel(nextResetAt: string | undefined, now: number) {
  if (!nextResetAt) return '—';
  const remaining = new Date(nextResetAt).getTime() - now;
  if (!Number.isFinite(remaining) || remaining <= 0) return 'обновится скоро';
  const totalSeconds = Math.floor(remaining / 1000);
  const hours = Math.floor(totalSeconds / 3600).toString().padStart(2, '0');
  const minutes = Math.floor((totalSeconds % 3600) / 60).toString().padStart(2, '0');
  const seconds = (totalSeconds % 60).toString().padStart(2, '0');
  return `${hours}:${minutes}:${seconds}`;
}

function stateSignature(state?: DailyBonusState) {
  return state ? `${state.day}:${state.claimed}:${state.reward ?? ''}` : '';
}

/**
 * Компактная панель ежедневного пазла. Выплата никогда не рассчитывается в
 * компоненте: `api.claimDailyBonus` возвращает уже зафиксированный сервером
 * результат, а анимация лишь визуализирует стирание билета.
 */
export function DailyBonusPanel({ token, profileState, compact = false }: DailyBonusPanelProps) {
  const client = useQueryClient();
  const [phase, setPhase] = useState<ScratchPhase>('idle');
  const [revealedReward, setRevealedReward] = useState<number | null>(null);
  const [error, setError] = useState('');
  const [popoverOpen, setPopoverOpen] = useState(false);
  const [now, setNow] = useState(() => Date.now());
  const mounted = useRef(true);
  const popoverRef = useRef<HTMLDivElement>(null);
  useEffect(() => () => { mounted.current = false; }, []);
  useEffect(() => {
    if (!compact) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [compact]);
  useEffect(() => {
    if (!compact || !popoverOpen) return;
    const onPointerDown = (event: PointerEvent) => {
      if (!popoverRef.current?.contains(event.target as Node)) setPopoverOpen(false);
    };
    const onKeyDown = (event: KeyboardEvent) => { if (event.key === 'Escape') setPopoverOpen(false); };
    document.addEventListener('pointerdown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => { document.removeEventListener('pointerdown', onPointerDown); document.removeEventListener('keydown', onKeyDown); };
  }, [compact, popoverOpen]);

  const { data } = useQuery({
    queryKey: ['daily-bonus'],
    queryFn: api.dailyBonus,
    enabled: Boolean(token),
    refetchInterval: 5000,
    staleTime: 1500,
    initialData: profileState,
    initialDataUpdatedAt: profileState ? Date.now() : undefined
  });

  // `/me` is refreshed more often than the dedicated endpoint. Prefer a
  // profile snapshot when it demonstrably contains newer progress, while a
  // just-claimed query result still wins over a stale profile response.
  const state = useMemo(() => {
    if (!data) return profileState;
    if (!profileState) return data;
    if (profileState.day !== data.day) return profileState;
    if (profileState.claimed && !data.claimed) return profileState;
    if (profileState.collected > data.collected) return profileState;
    return data;
  }, [data, profileState]);
  const progress = state ? Math.max(0, Math.min(100, Math.round((state.collected / Math.max(1, state.total)) * 100))) : 0;
  const reward = state?.claimed ? (phase === 'revealed' ? revealedReward ?? state.reward : state.reward) : null;
  const canScratch = Boolean(state?.ticketAvailable && !state.claimed && phase === 'idle');
  // The API persists the claim immediately. Keep the ticket mounted while the
  // visual scratch is still playing, otherwise a fast response would replace
  // the animation with the result before the player can see it.
  const showTicket = Boolean((state?.ticketAvailable && !state.claimed) || phase === 'scratching');
  const status = useMemo(() => {
    if (!state) return 'загрузка…';
    if (phase === 'scratching') return 'Стираем билет…';
    if (phase === 'revealed' || state.claimed) return 'Билет использован';
    if (state.ticketAvailable || state.eligible) return 'Билет готов';
    return `Соберите ${state.total} фрагментов`;
  }, [phase, state]);

  const signature = stateSignature(state);
  useEffect(() => {
    // A new day resets the card; an already used ticket opens in its stable result state.
    if (!state?.claimed) {
      if (phase === 'revealed') setPhase('idle');
      if (revealedReward !== null) setRevealedReward(null);
      return;
    }
    if (phase === 'idle' && state.reward != null) setRevealedReward(state.reward);
  }, [signature, state?.claimed, state?.reward, phase, revealedReward]);

  const scratch = async () => {
    if (!canScratch || !state) return;
    setError('');
    setPhase('scratching');
    try {
      // Claim first: the server reserves the reward atomically. We reveal it only
      // after the scratch animation has had time to play.
      const result = await api.claimDailyBonus();
      client.setQueryData(['daily-bonus'], result);
      client.invalidateQueries({ queryKey: ['me'] });
      await wait(1100);
      if (!mounted.current) return;
      setRevealedReward(result.reward);
      setPhase('revealed');
    } catch (value) {
      if (!mounted.current) return;
      setPhase('idle');
      setError(value instanceof Error ? value.message : 'Не удалось стереть билет');
      client.invalidateQueries({ queryKey: ['daily-bonus'] });
    }
  };

  if (!token) return null;

  if (compact) {
    return <div className="daily-puzzle-popover-wrap" ref={popoverRef}>
      <button type="button" className={`daily-puzzle-chip ${state?.eligible ? 'ready' : ''}`} onClick={() => setPopoverOpen((open) => !open)} aria-expanded={popoverOpen} aria-haspopup="dialog" aria-label="Открыть прогресс ежедневного пазла">
        <span className="daily-puzzle-chip-icon">🧩</span>
        <b>{state ? `${state.collected}/${state.total}` : '—/—'}</b>
        <small>{state?.nextResetAt ? `${Math.max(0, Math.floor((new Date(state.nextResetAt).getTime() - now) / 3_600_000))}ч` : '—'}</small>
        <span className="daily-puzzle-chip-chevron" aria-hidden="true">⌄</span>
      </button>
      {popoverOpen && <div className="daily-puzzle-popover" role="dialog" aria-label="Ежедневный пазл">
        <div className="daily-puzzle-popover-head"><div><strong>🧩 Ежедневный пазл</strong><small>Собирайте фрагменты за каждый раунд</small></div><button type="button" className="daily-puzzle-close" onClick={() => setPopoverOpen(false)} aria-label="Закрыть">×</button></div>
        <div className="daily-puzzle-popover-progress"><span>Прогресс: <b>{state ? `${state.collected} из ${state.total} фрагментов` : 'загрузка…'}</b></span><b>{progress}%</b></div>
        <div className="daily-progress-track" role="progressbar" aria-label="Прогресс дневного пазла" aria-valuemin={0} aria-valuemax={state?.total ?? 6} aria-valuenow={state?.collected ?? 0}><span style={{ width: `${progress}%` }} /></div>
        <div className="daily-puzzle-popover-meta"><span>⏱ Сброс через</span><b>{countdownLabel(state?.nextResetAt, now)}</b></div>
        <div className="daily-puzzle-popover-reward"><span>🎟 Награда</span><b>Лотерейный билет Столото</b><small>{state?.ticketAvailable ? 'Билет уже готов — сотрите его ниже' : 'Соберите все фрагменты, чтобы открыть билет'}</small></div>
        {showTicket ? <button type="button" className={`daily-ticket ${phase === 'scratching' ? 'scratching' : ''}`} onClick={scratch} disabled={!canScratch} aria-label="Стереть ежедневный лотерейный билет">
          <img src="/assets/daily-ticket.png" alt="Лотерейный билет" /><span className="daily-ticket-copy"><b>{phase === 'scratching' ? 'Стираем…' : 'Билет получен'}</b><small>{phase === 'scratching' ? 'Проверяем выигрыш' : 'Нажмите, чтобы стереть'}</small></span><span className="daily-scratch-cover" aria-hidden="true"><i>СТЕРЕТЬ</i></span>
        </button> : reward != null ? <div className="daily-ticket daily-ticket-result" aria-live="polite"><img src="/assets/bonus-coin.png" alt="Монетка бонусов" /><span className="daily-ticket-copy"><b>Ваш выигрыш</b><strong>{rewardLabel(reward)}</strong></span></div> : null}
        {error && <small className="daily-bonus-error" role="alert">{error}</small>}
        <a className="daily-puzzle-profile-link" href="/profile" onClick={() => setPopoverOpen(false)}>Перейти в профиль к пазлу <span aria-hidden="true">→</span></a>
      </div>}
    </div>;
  }

  return <section className={`daily-bonus-panel ${state?.eligible ? 'ready' : ''} ${phase !== 'idle' ? 'active' : ''}`} aria-label="Ежедневный бонус">
    <div className="daily-bonus-heading">
      <img src="/assets/daily-puzzle.png" alt="" className="daily-puzzle-icon" />
      <span>
        <small>ЕЖЕДНЕВНЫЙ ПАЗЛ</small>
        <b>{state ? `${state.collected} / ${state.total}` : '—'}</b>
      </span>
      <strong className="daily-bonus-status">{status}</strong>
    </div>
    <div className="daily-progress-track" role="progressbar" aria-label="Прогресс дневного пазла" aria-valuemin={0} aria-valuemax={state?.total ?? 6} aria-valuenow={state?.collected ?? 0}>
      <span style={{ width: `${progress}%` }} />
    </div>

    {showTicket ? <button type="button" className={`daily-ticket ${phase === 'scratching' ? 'scratching' : ''}`} onClick={scratch} disabled={!canScratch} aria-label="Стереть ежедневный лотерейный билет">
      <img src="/assets/daily-ticket.png" alt="Лотерейный билет" />
      <span className="daily-ticket-copy">
        <b>{phase === 'scratching' ? 'Стираем…' : 'Билет получен'}</b>
        <small>{phase === 'scratching' ? 'Проверяем выигрыш' : 'Нажмите, чтобы стереть'}</small>
      </span>
      <span className="daily-scratch-cover" aria-hidden="true"><i>СТЕРЕТЬ</i></span>
    </button> : reward != null ? <div className="daily-ticket daily-ticket-result" aria-live="polite">
      <img src="/assets/bonus-coin.png" alt="Монетка бонусов" />
      <span className="daily-ticket-copy"><b>Ваш выигрыш</b><strong>{rewardLabel(reward)}</strong></span>
    </div> : <div className="daily-bonus-hint">
      <span>{state?.eligible ? 'Билет доступен' : `Ещё ${Math.max(0, (state?.total ?? 6) - (state?.collected ?? 0))} фрагм.`}</span>
      <small>{resetLabel(state?.nextResetAt)}</small>
    </div>}

    {error && <small className="daily-bonus-error" role="alert">{error}</small>}
  </section>;
}
