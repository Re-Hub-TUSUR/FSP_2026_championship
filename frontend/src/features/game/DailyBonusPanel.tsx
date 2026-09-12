import { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, type DailyBonusState } from '../../shared/api/client';

type DailyBonusPanelProps = {
  token: string | null;
  /** /me already contains the state; it lets the panel render immediately. */
  profileState?: DailyBonusState;
};

type ScratchPhase = 'idle' | 'scratching' | 'revealed';

const wait = (ms: number) => new Promise<void>((resolve) => window.setTimeout(resolve, ms));

function rewardLabel(value: number | null | undefined) {
  return value == null ? '' : `+${value.toLocaleString('ru-RU')} ★`;
}

function resetLabel(nextResetAt?: string) {
  if (!nextResetAt) return '';
  const remaining = new Date(nextResetAt).getTime() - Date.now();
  if (!Number.isFinite(remaining) || remaining <= 0) return 'обновится скоро';
  const hours = Math.floor(remaining / 3_600_000);
  const minutes = Math.floor((remaining % 3_600_000) / 60_000);
  return `сброс через ${hours}ч ${minutes}м`;
}

function stateSignature(state?: DailyBonusState) {
  return state ? `${state.day}:${state.claimed}:${state.reward ?? ''}` : '';
}

/**
 * Компактная панель ежедневного пазла. Выплата никогда не рассчитывается в
 * компоненте: `api.claimDailyBonus` возвращает уже зафиксированный сервером
 * результат, а анимация лишь визуализирует стирание билета.
 */
export function DailyBonusPanel({ token, profileState }: DailyBonusPanelProps) {
  const client = useQueryClient();
  const [phase, setPhase] = useState<ScratchPhase>('idle');
  const [revealedReward, setRevealedReward] = useState<number | null>(null);
  const [error, setError] = useState('');
  const mounted = useRef(true);
  useEffect(() => () => { mounted.current = false; }, []);

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
