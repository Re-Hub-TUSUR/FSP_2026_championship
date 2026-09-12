import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, normalizeDailyBonus, type Round } from '../../shared/api/client';
import { fragmentArt } from '../game/PuzzleReward';

const finalMultiplier = (round: Round) => round.cashedOutMultiplier || round.multiplier || 1;
const fmt = (value: number) => value.toLocaleString('ru-RU');

export function Profile() {
  const queryClient = useQueryClient();
  const [localBalance, setLocalBalance] = useState<number | null>(null);
  const profileQuery = useQuery({ queryKey: ['me'], queryFn: api.me });
  const historyQuery = useQuery({ queryKey: ['history'], queryFn: api.history, staleTime: 30_000 });
  const leaderboardQuery = useQuery({ queryKey: ['leaderboard'], queryFn: api.leaderboard, staleTime: 30_000, retry: false });
  if (profileQuery.isLoading || !profileQuery.data) return <div className="card loading"><div className="spinner" />Загрузка профиля…</div>;

  const data = profileQuery.data;
  const daily = normalizeDailyBonus(data.dailyBonus);
  const total = daily?.total ?? data.puzzleFragments ?? 6;
  const mask = daily?.puzzleMask ?? data.puzzleMask ?? 0;
  const collected = Array.from({ length: total }, (_, i) => (mask & (1 << i)) !== 0);
  const collectedCount = daily?.collected ?? collected.filter(Boolean).length;
  const rounds = historyQuery.data ?? [];
  const best = rounds.reduce((max, round) => (round.payout && round.payout > 0 ? Math.max(max, finalMultiplier(round)) : max), 0);
  const rank = leaderboardQuery.data?.find((row) => row.id === data.id || row.username === data.username)?.rank;
  const level = data.level || 1;
  const xp = (data.points ?? 0) % 500;
  const progress = Math.min(100, Math.round((xp / 500) * 100));
  const onboardDate = new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'long' }).format(new Date());

  return <section className="profile-page">
    <div className="profile-hero-card"><div className="profile-avatar profile-avatar-large">{data.username.slice(0, 1).toUpperCase()}</div><div className="profile-identity"><span className="eyebrow">ВИЗИТКА ПИЛОТА</span><h1>{data.username}</h1><p>Капитан воздушного шара <span>•</span> На борту с {onboardDate}</p><div className="profile-level-row"><span>Пилот {level}-го ранга</span><b>{xp} / 500 XP</b></div><div className="profile-level-track"><span style={{ width: `${progress}%` }} /></div></div><span className="status-badge profile-status"><i /> В игре</span></div>
    <div className="profile-stats-grid">
      <div className="profile-stat-card balance-stat"><span className="profile-stat-icon">⛃</span><small>БОНУСНЫЙ БАЛАНС</small><strong>{fmt(localBalance ?? data.balance)}</strong><button type="button" onClick={async () => { try { const updated = await api.topUp(); setLocalBalance(updated.balance); queryClient.setQueryData(['me'], updated); } catch { setLocalBalance((localBalance ?? data.balance) + 500); } }}>＋ Пополнить</button></div>
      <div className="profile-stat-card points-stat"><span className="profile-stat-icon">⚡</span><small>ТУРНИРНЫЕ ОЧКИ</small><strong>{fmt(data.points ?? 0)}</strong><span>{rank ? `Место: #${rank} в топе` : 'Сыграйте раунд, чтобы войти в рейтинг'}</span></div>
      <div className="profile-stat-card record-stat"><span className="profile-stat-icon">🚀</span><small>РЕКОРД ПОЛЁТА</small><strong>{best ? `×${best.toFixed(2)}` : '×0.00'}</strong><span>{best ? 'Лучший cashout' : 'Ваш первый рекорд впереди'}</span></div>
      <div className="profile-stat-card ticket-stat"><span className="profile-stat-icon">🎟</span><small>БИЛЕТЫ СТОЛОТО</small><strong>{data.lotteryTickets ?? 0} шт.</strong><span>Из апсейла и пазлов</span></div>
    </div>
    <div className="profile-puzzle-card"><div className="profile-puzzle-header"><div><span className="eyebrow">ЕЖЕДНЕВНАЯ КОЛЛЕКЦИЯ</span><h2>Собери картину дня</h2><p>За каждый завершённый полёт вы получаете 1 фрагмент.</p></div><span className="profile-reset">⏳ Сброс через <b>{daily?.nextResetAt ? new Date(daily.nextResetAt).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' }) : '00:00'}</b></span></div><div className="profile-puzzle-content"><div className="profile-puzzle-grid">{collected.map((has, index) => <div key={index} className={`profile-puzzle-slot ${has ? 'has' : ''}`}><img src={has ? fragmentArt(index) : '/assets/daily-puzzle.png'} alt={has ? `Собранный фрагмент ${index + 1}` : `Пустой слот ${index + 1}`} /><span>{has ? 'Открыто' : '🧩 Пусто'}</span><small>{index + 1}</small></div>)}</div><aside className={`profile-reward-card ${daily?.eligible ? 'ready' : ''}`}><span className="profile-stat-icon">🎟</span><small>НАГРАДА ЗА СБОРКУ</small><h3>Лотерейный билет Столото</h3><p>Шанс выиграть от 10 до 2 000 бонусов</p><div className="profile-progress-label"><span>Прогресс сбора</span><b>{collectedCount} из {total}</b></div><div className="profile-level-track"><span style={{ width: `${Math.round((collectedCount / total) * 100)}%` }} /></div><Link className="profile-puzzle-cta" to="/play">Собрать пазл в игре <span>→</span></Link></aside></div></div>
  </section>;
}
