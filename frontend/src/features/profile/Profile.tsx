import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, normalizeDailyBonus, type Round } from '../../shared/api/client';
import { DailyBonusPanel } from '../game/DailyBonusPanel';

const finalMultiplier = (round: Round) => round.cashedOutMultiplier || round.multiplier || 1;
const number = (value: number) => Math.round(value).toLocaleString('ru-RU').replace(/ /g, ' ');

export function Profile() {
  const queryClient = useQueryClient();
  const [localBalance, setLocalBalance] = useState<number | null>(null);
  const profileQuery = useQuery({ queryKey: ['me'], queryFn: api.me });
  const historyQuery = useQuery({ queryKey: ['history'], queryFn: api.history, staleTime: 30_000 });
  const leaderboardQuery = useQuery({ queryKey: ['leaderboard'], queryFn: api.leaderboard, staleTime: 30_000, retry: false });
  if (profileQuery.isLoading || !profileQuery.data) return <div className="card"><div className="spinner" /></div>;

  const data = profileQuery.data;
  const daily = normalizeDailyBonus(data.dailyBonus);
  const total = daily?.total ?? data.puzzleFragments ?? 6;
  const mask = daily?.puzzleMask ?? data.puzzleMask ?? 0;
  const collected = Array.from({ length: total }, (_, i) => (mask & (1 << i)) !== 0);
  const rounds = historyQuery.data ?? [];
  const best = rounds.reduce((max, round) => (round.payout && round.payout > 0 ? Math.max(max, finalMultiplier(round)) : max), 0);
  const rank = leaderboardQuery.data?.find((row) => row.id === data.id || row.username === data.username)?.rank;
  const level = data.level || 1;
  const xp = (data.points ?? 0) % 500;
  const progress = Math.min(100, Math.round((xp / 500) * 100));
  const onboardDate = new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'long' }).format(new Date());

  const topUp = async () => {
    try {
      const updated = await api.topUp();
      setLocalBalance(updated.balance);
      queryClient.setQueryData(['me'], updated);
    } catch {
      setLocalBalance((localBalance ?? data.balance) + 500);
    }
  };

  return <div className="profile-grid">
    <section className="profile-hero">
      <span className="profile-avatar">{data.username.slice(0, 1).toUpperCase()}</span>
      <div style={{ flex: 1, minWidth: 0 }}>
        <span className="eyebrow">ИГРОК С {onboardDate.toUpperCase()}</span>
        <h1>{data.username}</h1>
        <div className="start-row" style={{ marginTop: 12, paddingTop: 0, border: 0 }}>
          <span className="muted">Пилот {level}-го ранга</span>
          <b className="num">{xp} / 500 XP</b>
        </div>
        <div className="level-bar"><i style={{ width: `${progress}%` }} /></div>
      </div>
      <span className="badge save">Аккаунт активен</span>
    </section>

    <div className="stat-card">
      <span className="eyebrow">БАЛАНС</span>
      <strong>{number(localBalance ?? data.balance)}</strong>
      <button className="ghost" onClick={topUp} style={{ marginTop: 12 }}>Пополнить на 500</button>
    </div>
    <div className="stat-card">
      <span className="eyebrow">ТУРНИРНЫЕ ОЧКИ</span>
      <strong>{number(data.points ?? 0)}</strong>
      <small className="muted">{rank ? `Место #${rank} в рейтинге` : 'Сыграйте раунд, чтобы войти в рейтинг'}</small>
    </div>
    <div className="stat-card">
      <span className="eyebrow">РЕКОРД ПОЛЁТА</span>
      <strong>{best ? `×${best.toFixed(2)}` : '×0.00'}</strong>
      <small className="muted">{best ? 'Лучшая фиксация выигрыша' : 'Ваш первый рекорд впереди'}</small>
    </div>

    <section className="card" style={{ gridColumn: '1 / -1' }}>
      <div className="section-title">
        <div>
          <span className="eyebrow">ЕЖЕДНЕВНАЯ КОЛЛЕКЦИЯ</span>
          <h2>Собери картину дня</h2>
          <p className="muted">За каждый завершённый полёт вы получаете один фрагмент.</p>
        </div>
        <span className="muted">Сброс в {daily?.nextResetAt ? new Date(daily.nextResetAt).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' }) : '00:00'}</span>
      </div>

      <div className="puzzle-collection">
        {collected.map((has, index) => <div key={index} className={`puzzle-slot ${has ? 'has' : ''}`}>
          <img src="/assets/puzzle.png" alt={has ? `Собранный фрагмент ${index + 1}` : `Пустой слот ${index + 1}`} />
          <small className="muted">{has ? 'Открыто' : `Слот ${index + 1}`}</small>
        </div>)}
      </div>

      <DailyBonusPanel token={localStorage.getItem('balloon-token')} profileState={daily ?? undefined} />

      <div className="start-row">
        <span className="muted">Билеты приходят из пазла дня и из предложения «Закрепи успех».</span>
        <Link className="primary" to="/play">Собрать пазл в игре <span aria-hidden="true">→</span></Link>
      </div>
    </section>
  </div>;
}
