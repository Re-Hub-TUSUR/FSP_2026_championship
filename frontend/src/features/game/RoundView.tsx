import { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../shared/api/client';

const FINISHED = ['SETTLED', 'CRASHED', 'CASHED_OUT'];

export function RoundView() {
  const { id = '' } = useParams();
  const nav = useNavigate();
  const client = useQueryClient();
  const [error, setError] = useState('');
  const [phase, setPhase] = useState<'flying' | 'crash' | 'celebrate'>('flying');
  const redirectTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const transitioning = useRef(false);
  // refetchIntervalInBackground: полёт продолжается, даже когда вкладка не в фокусе.
  const { data: round } = useQuery({ queryKey: ['round', id], queryFn: () => api.round(id), refetchInterval: 600, refetchIntervalInBackground: true });

  useEffect(() => {
    if (!round || !FINISHED.includes(round.state) || transitioning.current) return;
    transitioning.current = true;
    const won = (round.payout || 0) > 0 || round.state === 'CASHED_OUT' || round.state === 'SETTLED';
    setPhase(won ? 'celebrate' : 'crash');
    client.invalidateQueries({ queryKey: ['me'] });
    client.invalidateQueries({ queryKey: ['history'] });
    redirectTimer.current = setTimeout(() => nav(`/round/${id}/result`, { replace: true }), won ? 1050 : 1250);
  }, [round?.state, round?.payout, id, nav, client]);

  useEffect(() => () => { if (redirectTimer.current) clearTimeout(redirectTimer.current); }, []);

  if (!round) return <div className="card loading"><div className="spinner" />Загрузка полёта…</div>;

  const cashout = async () => {
    try {
      const settled = await api.cashout(id);
      transitioning.current = true;
      client.setQueryData(['round', id], settled);
      client.invalidateQueries({ queryKey: ['me'] });
      client.invalidateQueries({ queryKey: ['history'] });
      setPhase('celebrate');
      redirectTimer.current = setTimeout(() => nav(`/round/${id}/result`), 1050);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Не удалось забрать выигрыш');
    }
  };

  const ladder = round.ladder || [];
  const nextMultiplier = round.nextMultiplier ?? ladder[Math.min(round.level + 1, round.levels)];
  const potential = round.potentialPayout ?? Math.round(round.betAmount * round.multiplier);
  const progress = Math.min(100, (round.level / round.levels) * 100);

  const isTransitioning = phase !== 'flying';
  return <section className={`flight-card ${round.theme.toLowerCase()} phase-${phase}`}>
    <div className="flight-head">
      <div><span className="eyebrow">РАУНД {id.slice(-6).toUpperCase()}</span><h1>Шар в полёте</h1></div>
      <span className={`flight-status ${isTransitioning ? phase : ''}`}><i /> {phase === 'crash' ? 'ШАР ЛОПНУЛ' : phase === 'celebrate' ? 'ВЫИГРЫШ ЗАФИКСИРОВАН' : 'ИДЁТ ИГРА'}</span>
    </div>
    <div className="flight-scene">
      <div className="flight-cloud cloud c1" /><div className="flight-cloud cloud c2" />
      <img className="asset-balloon" src={round.theme === 'RED' ? '/art/balloon-red.svg' : '/art/balloon-green.svg'} alt="Воздушный шар" />
      {phase === 'crash' && <div className="flight-burst" aria-hidden="true">✦</div>}
      {phase === 'celebrate' && <div className="flight-confetti" aria-hidden="true"><i /><i /><i /><i /><i /></div>}
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
      <button className="cashout" disabled={round.level < 1 || isTransitioning} onClick={cashout}>{phase === 'celebrate' ? 'Выигрыш зафиксирован' : phase === 'crash' ? 'Раунд завершён' : 'Забрать выигрыш'} <b>{potential} ★</b></button>
      {error && <small className="error">{error}</small>}
    </div>
    <div className="commitment">🔒 Результат зафиксирован хешем <code>{round.commitment?.slice(0, 26)}…</code></div>
  </section>;
}
