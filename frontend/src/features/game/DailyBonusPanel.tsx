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

/** Keep this in sync with the cover wipe in styles.css. */
const SCRATCH_DURATION_MS = 1100;
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
  // StrictMode mounts effects twice in development (setup → cleanup → setup).
  // Re-arm the guard on every setup so the second, real effect run is allowed
  // to finish the claim animation.
  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);
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
  // The server marks a ticket as claimed before the visual wipe is finished.
  // Do not let that response replace the ticket while it is still scratching.
  const reward = phase === 'revealed'
    ? revealedReward ?? state?.reward ?? null
    : phase === 'idle' && state?.claimed
      ? state.reward
      : null;
  const canScratch = Boolean(state?.ticketAvailable && !state.claimed && phase === 'idle');
  // Keep the same button mounted for the complete wipe. The explicit phase
  // check also prevents a stale `ticketAvailable` response from bringing it
  // back after the reward has already been revealed.
  const showTicket = phase !== 'revealed' && Boolean(
    phase === 'scratching' || (state?.ticketAvailable && !state.claimed)
  );
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
      // Keep a response received during the wipe until it is revealed. A
      // profile refetch can still report `claimed: false` for a short time.
      if (phase !== 'scratching' && revealedReward !== null) setRevealedReward(null);
      return;
    }
    if (phase === 'idle' && state.reward != null && revealedReward !== state.reward) {
      setRevealedReward(state.reward);
    }
  }, [signature, state?.claimed, state?.reward, phase, revealedReward]);

  const scratch = async () => {
    if (!canScratch || !state) return;
    setError('');
    setPhase('scratching');
    const startedAt = Date.now();
    try {
      // Claim first: the server reserves the reward atomically. We reveal it only
      // after the scratch animation has had time to play.
      const result = await api.claimDailyBonus();
      if (!mounted.current) return;
      // Keep the reward in local state while the query cache is updated. The
      // render branch above deliberately hides it until phase becomes revealed.
      setRevealedReward(result.reward);
      client.setQueryData(['daily-bonus'], result);
      client.invalidateQueries({ queryKey: ['me'] });
      await wait(Math.max(0, SCRATCH_DURATION_MS - (Date.now() - startedAt)));
      if (!mounted.current) return;
      setPhase('revealed');
    } catch (value) {
      if (!mounted.current) return;
      // Let a failed request settle after the same visual interval so a fast
      // error cannot flash the cover on and off in a single frame.
      await wait(Math.max(0, SCRATCH_DURATION_MS - (Date.now() - startedAt)));
      if (!mounted.current) return;
      setRevealedReward(null);
      setPhase('idle');
      setError(value instanceof Error ? value.message : 'Не удалось стереть билет');
      client.invalidateQueries({ queryKey: ['daily-bonus'] });
    }
  };

  // Защитный слой стирается анимацией, пока сервер уже зафиксировал награду; результат появляется после неё.
  const ticket = showTicket
    ? <button type="button" className={`daily-ticket ${phase === 'scratching' ? 'scratching' : ''}`} onClick={scratch} disabled={!canScratch} aria-busy={phase === 'scratching'} aria-label="Стереть ежедневный лотерейный билет">
      <img src="/assets/ticket.png" alt="" />
      <span className="daily-ticket-copy">
        <b>{phase === 'scratching' ? 'Стираем…' : 'Билет получен'}</b>
        <small>{phase === 'scratching' ? 'Проверяем выигрыш' : 'Нажмите, чтобы стереть'}</small>
      </span>
      <span className="daily-scratch-cover" aria-hidden="true"><i>СТЕРЕТЬ</i></span>
    </button>
    : reward != null
      ? <div className="daily-ticket daily-ticket-result" aria-live="polite">
        <img src="/assets/coin.png" alt="" />
        <span className="daily-ticket-copy"><b>Ваш выигрыш</b><strong>{rewardLabel(reward)}</strong></span>
      </div>
      : null;

  if (!token) return null;

  if (compact) {
    return <div className="daily-popover-wrap" ref={popoverRef}>
      <button type="button" className={`puzzle-chip ${state?.eligible ? 'ready' : ''}`} onClick={() => setPopoverOpen((open) => !open)} aria-expanded={popoverOpen} aria-haspopup="dialog" aria-label="Открыть прогресс ежедневного пазла">
        <img src="/assets/puzzle.png" alt="" width={20} height={20} />
        <span><small>ПАЗЛ ДНЯ</small><b>{state ? `${state.collected} / ${state.total}` : '— / —'}</b></span>
      </button>

      {popoverOpen && <div className="daily-popover" role="dialog" aria-label="Ежедневный пазл">
        <div className="modal-head">
          <div><span className="eyebrow">ЕЖЕДНЕВНЫЙ ПАЗЛ</span><h3>Собирайте фрагменты за каждый раунд</h3></div>
          <button type="button" className="modal-close" onClick={() => setPopoverOpen(false)} aria-label="Закрыть">×</button>
        </div>

        <div className="level-label">
          <span>{state ? `${state.collected} из ${state.total} фрагментов` : 'загрузка…'}</span>
          <b>{progress}%</b>
        </div>
        <div className="level-bar" role="progressbar" aria-label="Прогресс дневного пазла" aria-valuemin={0} aria-valuemax={state?.total ?? 6} aria-valuenow={state?.collected ?? 0}>
          <i style={{ width: `${progress}%` }} />
        </div>

        <div className="level-label"><span>Сброс через</span><b className="num">{countdownLabel(state?.nextResetAt, now)}</b></div>

        <div className="upsell-offer">
          <img src="/assets/ticket.png" alt="" width={44} height={44} />
          <div>
            <b>Лотерейный билет Столото</b>
            <small className="muted">{state?.ticketAvailable ? 'Билет готов — сотрите его ниже' : 'Соберите все фрагменты, чтобы открыть билет'}</small>
          </div>
        </div>

        {ticket}

        {error && <div className="form-error" role="alert">{error}</div>}
        <a className="text-button" href="/profile" onClick={() => setPopoverOpen(false)}>Перейти в профиль к пазлу →</a>
      </div>}
    </div>;
  }

  return <section className="card" aria-label="Ежедневный бонус">
    <div className="section-title">
      <div>
        <span className="eyebrow">ЕЖЕДНЕВНЫЙ ПАЗЛ</span>
        <h2>{state ? `${state.collected} / ${state.total}` : '—'}</h2>
      </div>
      <span className="muted">{status}</span>
    </div>

    <div className="level-bar" role="progressbar" aria-label="Прогресс дневного пазла" aria-valuemin={0} aria-valuemax={state?.total ?? 6} aria-valuenow={state?.collected ?? 0}>
      <i style={{ width: `${progress}%` }} />
    </div>

    {ticket ?? <p className="muted">
      {state?.eligible ? 'Билет доступен' : `Ещё ${Math.max(0, (state?.total ?? 6) - (state?.collected ?? 0))} фрагментов`} · {resetLabel(state?.nextResetAt)}
    </p>}

    {error && <div className="form-error" role="alert">{error}</div>}
  </section>;
}
