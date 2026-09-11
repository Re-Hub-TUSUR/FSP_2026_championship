import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../shared/api/client';

const FINISHED = ['SETTLED', 'CRASHED', 'CASHED_OUT'];

export function RoundView() {
  const { id = '' } = useParams();
  const nav = useNavigate();
  const client = useQueryClient();
  const [error, setError] = useState('');
  // refetchIntervalInBackground: полёт продолжается, даже когда вкладка не в фокусе.
  const { data: round } = useQuery({ queryKey: ['round', id], queryFn: () => api.round(id), refetchInterval: 600, refetchIntervalInBackground: true });

  useEffect(() => {
    if (!round || !FINISHED.includes(round.state)) return;
    client.invalidateQueries({ queryKey: ['me'] });
    client.invalidateQueries({ queryKey: ['history'] });
    nav(`/round/${id}/result`, { replace: true });
  }, [round?.state, id, nav, client]);

  if (!round) return <div className="card loading"><div className="spinner" />Загрузка полёта…</div>;

  const cashout = async () => {
    try {
      client.setQueryData(['round', id], await api.cashout(id));
      client.invalidateQueries({ queryKey: ['me'] });
      client.invalidateQueries({ queryKey: ['history'] });
      nav(`/round/${id}/result`);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Не удалось забрать выигрыш');
    }
  };

  const ladder = round.ladder || [];
  const nextMultiplier = round.nextMultiplier ?? ladder[Math.min(round.level + 1, round.levels)];
  const potential = round.potentialPayout ?? Math.round(round.betAmount * round.multiplier);
  const progress = Math.min(100, (round.level / round.levels) * 100);

  return <section className={`flight-card ${round.theme.toLowerCase()}`}>
    <div className="flight-head">
      <div><span className="eyebrow">РАУНД {id.slice(-6).toUpperCase()}</span><h1>Шар в полёте</h1></div>
      <span className="flight-status"><i /> ИДЁТ ИГРА</span>
    </div>
    <div className="flight-scene">
      <div className="flight-cloud cloud c1" /><div className="flight-cloud cloud c2" />
      <img className="asset-balloon" src={round.theme === 'RED' ? '/art/balloon-red.svg' : '/art/balloon-green.svg'} alt="Воздушный шар" />
      <div className="flight-multiplier">×{round.multiplier.toFixed(2)}</div>
    </div>
    <div className="level-label"><span>УРОВЕНЬ {round.level} / {round.levels}</span><b>+{round.points} очков</b></div>
    <div className="progress">
      <div style={{ width: `${progress}%` }} />
      {Array.from({ length: round.levels }, (_, i) => <i key={i} className={i < round.level ? 'on' : ''} style={{ left: `${(i / Math.max(1, round.levels - 1)) * 100}%` }} />)}
    </div>
    <div className="flight-meta">
      <div><small>СТАВКА</small><b>{round.betAmount} ★</b></div>
      <div><small>СЛЕДУЮЩИЙ УРОВЕНЬ</small><b>×{(nextMultiplier || round.multiplier).toFixed(2)}</b></div>
      <div><small>МАКСИМУМ ТЕМЫ</small><b>×{(ladder[round.levels] || round.multiplier).toFixed(2)}</b></div>
    </div>
    <div className="flight-actions">
      <p>Нажмите кнопку, чтобы зафиксировать текущий выигрыш</p>
      <button className="cashout" disabled={round.level < 1} onClick={cashout}>Забрать выигрыш <b>{potential} ★</b></button>
      {error && <small className="error">{error}</small>}
    </div>
    <div className="commitment">🔒 Результат зафиксирован хешем <code>{round.commitment?.slice(0, 26)}…</code></div>
  </section>;
}
