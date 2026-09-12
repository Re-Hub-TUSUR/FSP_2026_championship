import { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../shared/api/client';
import { CrashHistory } from './CrashHistory';

export function RoundView() {
  const { id = '' } = useParams();
  const nav = useNavigate();
  const client = useQueryClient();
  const [error, setError] = useState('');
  const [gain, setGain] = useState<{ value: number; key: number } | null>(null);
  const [hint, setHint] = useState(() => !localStorage.getItem('balloon-onboarded'));
  const lastPoints = useRef<number | null>(null);
  const redirectTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const transitioning = useRef(false);
  // refetchIntervalInBackground: полёт продолжается, даже когда вкладка не в фокусе.
  const { data: round } = useQuery({ queryKey: ['round', id], queryFn: () => api.round(id), refetchInterval: 600, refetchIntervalInBackground: true });

  const flightOver = Boolean(round?.flightOver);
  const cashedOut = Boolean(round?.cashedOut ?? round?.cashedOutMultiplier);
  const won = (round?.payout || 0) > 0;

  useEffect(() => {
    if (!round || !flightOver || transitioning.current) return;
    transitioning.current = true;
    client.invalidateQueries({ queryKey: ['me'] });
    client.invalidateQueries({ queryKey: ['daily-bonus'] });
    client.invalidateQueries({ queryKey: ['history'] });
    client.invalidateQueries({ queryKey: ['crashes'] });
    // Пауза, чтобы игрок увидел взрыв шара, и только затем открывается результат.
    redirectTimer.current = setTimeout(() => nav(`/round/${id}/result`, { replace: true }), 1400);
  }, [flightOver, id, nav, client, round]);

  useEffect(() => () => { if (redirectTimer.current) clearTimeout(redirectTimer.current); }, []);

  // Всплывающее «+X» при пересечении уровня.
  useEffect(() => {
    if (!round) return;
    const previous = lastPoints.current;
    lastPoints.current = round.points;
    if (previous === null || round.points <= previous) return;
    const key = Date.now();
    setGain({ value: round.points - previous, key });
    const timer = setTimeout(() => setGain((current) => (current?.key === key ? null : current)), 1100);
    return () => clearTimeout(timer);
  }, [round?.points]);

  // Мини-онбординг показывается один раз при первом полёте.
  useEffect(() => {
    if (!hint) return;
    localStorage.setItem('balloon-onboarded', '1');
    const timer = setTimeout(() => setHint(false), 4000);
    return () => clearTimeout(timer);
  }, [hint]);

  if (!round) return <div className="card loading"><div className="spinner" />Загрузка полёта…</div>;

  const cashout = async () => {
    try {
      client.setQueryData(['round', id], await api.cashout(id));
      client.invalidateQueries({ queryKey: ['me'] });
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Не удалось забрать выигрыш');
    }
  };

  const ladder = round.ladder || [];
  const fixedMultiplier = round.cashedOutMultiplier || 0;
  const potential = round.potentialPayout ?? Math.round(round.betAmount * round.multiplier);
  const progress = Math.min(100, (round.level / round.levels) * 100);
  const hasBooster = (round.boosterLevel ?? 0) > 0 && round.boosterMultiplier > 1;
  const boosterPosition = ((Math.max(1, round.boosterLevel ?? 1) - 1) / Math.max(1, round.levels - 1)) * 100;
  const boosterState = round.boosterActivated ? 'active' : cashedOut ? 'lost' : 'waiting';
  const missedGain = cashedOut && !flightOver && round.multiplier > fixedMultiplier;

  const phase = flightOver ? (won ? 'celebrate' : 'crash') : cashedOut ? 'secured' : 'flying';
  const status = phase === 'crash' ? 'ШАР ЛОПНУЛ'
    : phase === 'celebrate' ? 'РАУНД ЗАВЕРШЁН'
    : phase === 'secured' ? 'ВЫИГРЫШ ЗАФИКСИРОВАН'
    : 'ИДЁТ ИГРА';

  return <section className={`flight-card ${round.theme.toLowerCase()} phase-${phase}`}>
    <div className="flight-head">
      <div><span className="eyebrow">РАУНД {id.slice(-6).toUpperCase()}</span><h1>Шар в полёте</h1></div>
      <span className={`flight-status ${phase}`}><i /> {status}</span>
    </div>
    <CrashHistory />
    <div className="flight-scene">
      <div className="flight-cloud cloud c1" /><div className="flight-cloud cloud c2" />
      <img className="asset-balloon" src={round.theme === 'RED' ? '/art/balloon-red.svg' : '/art/balloon-green.svg'} alt="Воздушный шар" />
      {phase === 'crash' && <div className="flight-burst" aria-hidden="true">✦</div>}
      {phase === 'celebrate' && <div className="flight-confetti" aria-hidden="true"><i /><i /><i /><i /><i /></div>}
      <div className={`flight-multiplier tier-${round.multiplier >= 3 ? 'high' : round.multiplier >= 2 ? 'mid' : round.multiplier >= 1.5 ? 'warm' : 'base'}`}>×{round.multiplier.toFixed(2)}</div>
      {round.boosterActivated && <div className="booster-flash" aria-hidden="true">×{round.boosterMultiplier} БУСТЕР</div>}
      {gain && <div className="points-gain" key={gain.key} aria-hidden="true">+{gain.value}</div>}
    </div>

    <div className="level-label"><span>УРОВЕНЬ {round.level} / {round.levels}</span><b>+{round.points} очков</b></div>
    <div className="progress">
      <div style={{ width: `${progress}%` }} />
      {Array.from({ length: round.levels }, (_, i) => <i key={i} className={i < round.level ? 'on' : ''} style={{ left: `${(i / Math.max(1, round.levels - 1)) * 100}%` }} />)}
      {hasBooster && <span className={`booster-marker ${boosterState}`} style={{ left: `${boosterPosition}%` }} title={`Бустер ×${round.boosterMultiplier} на уровне ${round.boosterLevel}`}>★</span>}
    </div>

    <div className="flight-meta">
      <div><small>СТАВКА</small><b>{round.betAmount} ★</b></div>
      <div><small>СЛЕДУЮЩИЙ УРОВЕНЬ</small><b>×{(round.nextMultiplier || round.multiplier).toFixed(2)}</b></div>
      <div><small>{hasBooster ? 'БУСТЕР' : 'МАКСИМУМ'}</small><b>{hasBooster ? `×${round.boosterMultiplier} · ур. ${round.boosterLevel}` : `×${(ladder[round.levels] || round.multiplier).toFixed(2)}`}</b></div>
    </div>

    <div className="flight-actions">
      {cashedOut
        ? <p className="secured-note">Выигрыш зафиксирован: <b>{round.payout} ★</b> по коэффициенту ×{fixedMultiplier.toFixed(2)}. Шар летит дальше — результат откроется после взрыва.</p>
        : <p>Нажмите кнопку, чтобы зафиксировать текущий выигрыш</p>}
      <div className="cashout-wrap">
        {hint && !cashedOut && <span className="cashout-hint">Нажми «Забрать» до того, как шар лопнет<i /></span>}
        <button className="cashout" disabled={round.level < 1 || cashedOut || flightOver} onClick={cashout}>
          {cashedOut ? 'Выигрыш забран' : 'Забрать выигрыш'} <b>{cashedOut ? round.payout : potential} ★</b>
        </button>
      </div>
      {missedGain && <small className="missed-note">Могли бы забрать больше: сейчас ×{round.multiplier.toFixed(2)}</small>}
      {error && <small className="error">{error}</small>}
    </div>
    <div className="commitment">🔒 Результат зафиксирован хешем <code>{round.commitment?.slice(0, 26)}…</code></div>
  </section>;
}
