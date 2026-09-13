import { useEffect, useRef, useState } from 'react';
import type { CSSProperties } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../shared/api/client';
import { MIN_CASHOUT_LEVEL } from '../../shared/game/engine';
import { CrashHistory } from './CrashHistory';
import { RulesDialog } from './RulesDialog';
import { Top5Panel } from './Top5Panel';

/** Осколки взрыва и конфетти: разлёт задаётся переменными --dx/--dy/--r. */
const SHARDS = [
  { dx: '-120px', dy: '-90px', r: '-140deg', color: '#ffd9a1' },
  { dx: '-60px', dy: '-140px', r: '120deg', color: '#ff9c7a' },
  { dx: '30px', dy: '-150px', r: '-90deg', color: '#ffe9b8' },
  { dx: '110px', dy: '-80px', r: '160deg', color: '#ff8b7d' },
  { dx: '-100px', dy: '40px', r: '80deg', color: '#ffc48a' },
  { dx: '96px', dy: '58px', r: '-60deg', color: '#ffb0a0' }
];
const CONFETTI = ['-150px', '-110px', '-70px', '-30px', '0px', '30px', '70px', '110px', '150px', '-190px', '190px'];
const COINS = [
  { dx: '-190px', dy: '-210px' }, { dx: '-90px', dy: '-250px' }, { dx: '10px', dy: '-270px' },
  { dx: '110px', dy: '-250px' }, { dx: '210px', dy: '-210px' }
];

/** Столько шар доводится до нового уровня — совпадает с --dur-flight в styles.css. */
const RISE_MS = 1200;

const tierOf = (multiplier: number) =>
  multiplier >= 3 ? 'high' : multiplier >= 2 ? 'mid' : multiplier >= 1.5 ? 'warm' : 'base';

const number = (value: number) => Math.round(value).toLocaleString('ru-RU').replace(/ /g, ' ');

export function RoundView() {
  const { id = '' } = useParams();
  const nav = useNavigate();
  const client = useQueryClient();
  const [error, setError] = useState('');
  const [gain, setGain] = useState<{ value: number; key: number } | null>(null);
  const [coins, setCoins] = useState(false);
  const [rulesOpen, setRulesOpen] = useState(false);
  const [hint, setHint] = useState(() => !localStorage.getItem('balloon-onboarded'));
  const lastPoints = useRef<number | null>(null);
  const redirectTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const transitioning = useRef(false);
  // refetchIntervalInBackground: полёт продолжается, даже когда вкладка не в фокусе.
  const { data: round } = useQuery({ queryKey: ['round', id], queryFn: () => api.round(id), refetchInterval: 500, refetchIntervalInBackground: true });

  const flightOver = Boolean(round?.flightOver);
  const cashedOut = Boolean(round?.cashedOut ?? round?.cashedOutMultiplier);
  // Раунд, прерванный перезапуском сервера до cashout, отменяется с возвратом ставки — это не выигрыш.
  const refunded = round?.state === 'REFUNDED';
  const won = !refunded && (round?.payout || 0) > 0;
  // Сервер заканчивает полёт, не поднимая level на уровень взрыва: там остаётся последний пройденный уровень.
  // Чтобы шар лопался на том уровне, который засчитан, после конца полёта он доводится до уровня взрыва или вершины.
  const endLevel = round && flightOver && !refunded && round.popLevel ? Math.min(round.popLevel, round.levels) : round?.level ?? 0;
  const risesToEnd = Boolean(round) && endLevel > (round?.level ?? 0);
  const [arrived, setArrived] = useState(false);

  useEffect(() => {
    if (!flightOver) return;
    // Взрыв или конфетти показываются, когда шар долетел до уровня, на котором закончился полёт.
    const timer = setTimeout(() => setArrived(true), risesToEnd ? RISE_MS : 0);
    return () => clearTimeout(timer);
  }, [flightOver, risesToEnd]);

  useEffect(() => {
    if (!round || !flightOver || transitioning.current) return;
    transitioning.current = true;
    client.invalidateQueries({ queryKey: ['me'] });
    client.invalidateQueries({ queryKey: ['daily-bonus'] });
    client.invalidateQueries({ queryKey: ['history'] });
    client.invalidateQueries({ queryKey: ['crashes'] });
    // Пауза, чтобы игрок увидел подъём до уровня взрыва и сам взрыв, и только затем открывается результат.
    redirectTimer.current = setTimeout(() => nav(`/round/${id}/result`, { replace: true }), (risesToEnd ? RISE_MS : 0) + 1900);
  }, [flightOver, id, nav, client, round, risesToEnd]);

  useEffect(() => () => { if (redirectTimer.current) clearTimeout(redirectTimer.current); }, []);

  // Всплывающее «+X» при пересечении уровня.
  useEffect(() => {
    if (!round) return;
    const previous = lastPoints.current;
    lastPoints.current = round.points;
    if (previous === null || round.points <= previous) return;
    const key = Date.now();
    setGain({ value: round.points - previous, key });
    const timer = setTimeout(() => setGain((current) => (current?.key === key ? null : current)), 1200);
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
      setCoins(true);
      setTimeout(() => setCoins(false), 1900);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Не удалось забрать выигрыш');
    }
  };

  const levels = round.levels;
  // Уровень на сцене: в полёте — последний пройденный, после конца полёта — уровень взрыва или вершина.
  const level = flightOver ? endLevel : round.level;
  // Коэффициент на табло после конца полёта — коэффициент уровня, на котором шар лопнул.
  const shownMultiplier = flightOver && !refunded ? round.crashMultiplier ?? round.multiplier : round.multiplier;
  const ladder = round.ladder || [];
  const fixedMultiplier = round.cashedOutMultiplier || 0;
  const potential = round.potentialPayout ?? Math.round(round.betAmount * round.multiplier);
  const hasBooster = (round.boosterLevel ?? 0) > 0 && round.boosterMultiplier > 1;
  const boosterFired = Boolean(round.boosterActivated);
  const boosterPosition = ((Math.max(1, round.boosterLevel ?? 1) - 1) / Math.max(1, levels - 1)) * 100;
  const boosterState = boosterFired ? 'active' : cashedOut || flightOver ? 'lost' : 'waiting';

  // Пока шар поднимается к уровню взрыва, сцена остаётся в полёте; итог раунда показывается по прибытии.
  const phase = flightOver && arrived ? (refunded ? 'refunded' : won ? 'celebrate' : 'crash') : cashedOut ? 'secured' : 'flying';
  const status = phase === 'crash' ? 'ШАР ЛОПНУЛ'
    : phase === 'celebrate' ? 'РАУНД ЗАВЕРШЁН'
    : phase === 'refunded' ? 'СТАВКА ВОЗВРАЩЕНА'
    : phase === 'secured' ? 'ВЫИГРЫШ ЗАФИКСИРОВАН'
    : null;

  // Лента: 100% — луг у нижней кромки кадра, 0% — космос. Позиция считается от уровня, не от таймера.
  const skyY = `${(100 - (Math.min(level, levels) / levels) * 100).toFixed(2)}%`;
  // Облака живут только в своём слое атмосферы: у земли их мало, в космосе нет совсем.
  const altitude = Math.min(level, levels) / levels;
  const cloudBand = altitude <= 0.14 ? 0.34 : altitude <= 0.34 ? 0.72 : altitude <= 0.62 ? 1 : altitude <= 0.8 ? 0.45 : 0;

  const marks = Array.from({ length: levels }, (_, index) => {
    const target = index + 1;
    const raw = ladder[target] ?? 0;
    const value = boosterFired && target >= (round.boosterLevel ?? 0) ? raw * round.boosterMultiplier : raw;
    return {
      level: target,
      label: `×${value.toFixed(2)}`,
      state: target < level ? 'passed' : target === level ? 'current' : 'future'
    };
  });

  const note = phase === 'secured'
    ? `Выигрыш ${number(round.payout || 0)} очков зафиксирован по ×${fixedMultiplier.toFixed(2)}. Шар летит дальше${round.multiplier > fixedMultiplier ? ` — могли бы забрать больше: сейчас ×${round.multiplier.toFixed(2)}` : ''}`
    : phase === 'crash' ? `Шар лопнул на уровне ${round.popLevel ?? level + 1} из ${levels}. Ставка не возвращается`
    : phase === 'celebrate' ? (round.state === 'SETTLED'
      ? `Шар дошёл до вершины — выплата ${number(round.payout || 0)} начислена автоматически`
      : `Шар лопнул на уровне ${level} из ${levels}, но выигрыш ${number(round.payout || 0)} уже был зафиксирован`)
    : phase === 'refunded' ? 'Полёт прервался из-за перезапуска сервера — ставка вернулась на баланс'
    : flightOver ? ''
    : level < MIN_CASHOUT_LEVEL ? 'Первый подъём проходит без риска — забрать выигрыш можно со второго уровня'
    : 'Нажмите кнопку, чтобы зафиксировать текущий выигрыш';

  const button = phase === 'secured' ? { label: 'Выигрыш забран', amount: round.payout || 0, disabled: true }
    : phase === 'crash' ? { label: 'Раунд завершён', amount: 0, disabled: true }
    : phase === 'celebrate' ? { label: 'Выплата начислена', amount: round.payout || 0, disabled: true }
    : phase === 'refunded' ? { label: 'Ставка возвращена', amount: round.betAmount, disabled: true }
    : { label: 'Забрать выигрыш', amount: level < MIN_CASHOUT_LEVEL ? round.betAmount : potential, disabled: level < MIN_CASHOUT_LEVEL };

  const sceneStyle = { '--level': level, '--sky-y': skyY } as CSSProperties;
  const progressStyle = { '--progress': `${Math.min(100, (level / levels) * 100)}%` } as CSSProperties;

  return <section className={`flight-screen phase-${phase}`}>
    <div className="flight-scene" style={sceneStyle}>
      <div className="sky-ribbon" />
      <div className="flight-clouds">
        <img className="cloud-far" src="/assets/cloud-a.png" alt="" style={{ opacity: cloudBand * 0.5 }} />
        <img className="cloud-mid" src="/assets/cloud-b.png" alt="" style={{ opacity: cloudBand * 0.42 }} />
        <img className="cloud-near" src="/assets/cloud-c.png" alt="" style={{ opacity: cloudBand * 0.66 }} />
      </div>
      <div className="altitude-line" />

      <div className="altitude-scale">
        <div className="altitude-track">
          {marks.map((mark) => <div key={mark.level} className={`altitude-mark ${mark.state}`}>
            <span>{mark.label}</span><i />
          </div>)}
        </div>
      </div>

      <div className="flight-column">
        <div className={`flight-multiplier tier-${tierOf(shownMultiplier)}`} key={level}>
          <span>×{shownMultiplier.toFixed(2)}</span>
        </div>
        <img className="asset-balloon" src={round.theme === 'GREEN' ? '/assets/balloon-green.png' : '/assets/balloon-red.png'} alt="Воздушный шар в полёте" />
        {boosterFired && <div className="booster-flash"><span>×{round.boosterMultiplier} БУСТЕР</span></div>}
        {phase === 'secured' && !boosterFired && <div className="secured-badge">ЗАФИКСИРОВАНО ×{fixedMultiplier.toFixed(2)}</div>}
        {gain && <span className="points-gain" key={gain.key}>+{gain.value}</span>}
      </div>

      {phase === 'crash' && <>
        <div className="flight-burst" />
        <div className="crash-veil" />
        <div className="flight-shards">
          {SHARDS.map((shard, index) => <i
            key={index}
            style={{ '--dx': shard.dx, '--dy': shard.dy, '--r': shard.r, background: shard.color, left: '50%', bottom: 'var(--flight-line)', width: '12px', height: '18px' } as CSSProperties}
          />)}
        </div>
      </>}

      {phase === 'celebrate' && <div className="flight-confetti">
        {CONFETTI.map((dx, index) => <i
          key={index}
          style={{ '--dx': dx, left: '50%', bottom: 'var(--flight-line)', background: index % 3 === 0 ? '#f2ca55' : index % 3 === 1 ? '#57b9f3' : '#4fd19d', animationDelay: `${index * 0.05}s` } as CSSProperties}
        />)}
      </div>}

      {coins && <div className="coin-fly">
        {COINS.map((coin, index) => <img
          key={index}
          src="/assets/coin.png"
          alt=""
          style={{ '--dx': coin.dx, '--dy': coin.dy, left: '50%', bottom: '30%', width: '38px', animationDelay: `${index * 0.08}s` } as CSSProperties}
        />)}
      </div>}

      <div className="flight-controls">
        <button type="button" onClick={() => nav('/play')}><span aria-hidden="true">←</span> Назад</button>
        <button type="button" onClick={() => setRulesOpen(true)}>Правила игры</button>
      </div>

      <div className="flight-topline">
        <span className="round-tag">РАУНД {id.slice(-6).toUpperCase()}</span>
        {status && <span className={`flight-status ${phase}`}><i /> {status}</span>}
      </div>

      <Top5Panel />

      <div className="flight-hud">
        <CrashHistory onDark />

        <div className="flight-hud-row">
        <div className="flight-hud-progress">
          <div className="level-label">
            <span>УРОВЕНЬ {level} / {levels}</span>
            <b>+{round.points} очков</b>
          </div>
          <div className="progress" style={progressStyle}>
            <div />
            {Array.from({ length: levels }, (_, index) => <i
              key={index}
              className={index < level ? 'on' : ''}
              style={{ left: `${(index / Math.max(1, levels - 1)) * 100}%` }}
            />)}
            {hasBooster && <span
              className={`booster-marker ${boosterState}`}
              style={{ left: `${boosterPosition}%` }}
              title={`Бустер ×${round.boosterMultiplier} на уровне ${round.boosterLevel}`}
            >★</span>}
          </div>
          <span className={phase === 'secured' ? 'secured-note' : phase === 'crash' || phase === 'celebrate' ? 'missed-note' : 'muted-on-dark'}>{note}</span>
        </div>

        <div className="flight-meta">
          <div>
            <small>СТАВКА</small>
            <b>{number(round.betAmount)} <img src="/assets/coin.png" alt="" width={18} height={18} /></b>
          </div>
          <div>
            <small>СЛЕДУЮЩИЙ УРОВЕНЬ</small>
            <b>×{(round.nextMultiplier || round.multiplier).toFixed(2)}</b>
          </div>
          <div className={hasBooster ? 'is-booster' : ''}>
            <small>{hasBooster ? 'БУСТЕР' : 'МАКСИМУМ'}</small>
            <b>{hasBooster ? `×${round.boosterMultiplier} · ур. ${round.boosterLevel}` : `×${(ladder[levels] || round.multiplier).toFixed(2)}`}</b>
          </div>
        </div>

        <div className={`cashout-wrap ${!button.disabled && !flightOver ? 'is-live' : ''}`}>
          {hint && !cashedOut && <span className="cashout-hint">Нажми «Забрать» до того, как шар лопнет<i /></span>}
          <button className="cashout" disabled={button.disabled || flightOver} onClick={cashout}>
            {button.label}
            <b>{number(button.amount)} <img src="/assets/coin.png" alt="" width={22} height={22} /></b>
          </button>
        </div>
        </div>
        {error && <small className="missed-note">{error}</small>}

        <div className="commitment">
          <i />
          Результат зафиксирован хешем <code>{round.commitment?.slice(0, 26)}…</code>
        </div>
      </div>
    </div>

    {rulesOpen && <RulesDialog onClose={() => setRulesOpen(false)} />}
  </section>;
}
