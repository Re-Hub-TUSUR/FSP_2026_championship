import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, type LeaderRow, type Tournament } from '../../shared/api/client';
import { useStore } from '../../store';
import { RulesDialog } from './RulesDialog';

/** Стопка монет растёт вместе с номиналом ставки. */
const BET_ART = ['/assets/coins-3.png', '/assets/coins-5.png', '/assets/coins-9.png', '/assets/coins-14.png'];
const number = (value: number) => Math.round(value).toLocaleString('ru-RU').replace(/ /g, ' ');

function tournamentRemaining(endsAt: string) {
  const end = new Date(endsAt).getTime();
  if (!Number.isFinite(end)) return '—';
  const totalMinutes = Math.ceil(Math.max(0, end - Date.now()) / 60_000);
  const days = Math.floor(totalMinutes / 1440);
  const hours = Math.floor((totalMinutes % 1440) / 60);
  const minutes = totalMinutes % 60;
  return days > 0 ? `${days} дн. ${hours} ч.` : `${hours} ч. ${minutes} мин.`;
}

/** Турнир прямо с экрана ставки: срок, призы и первая десятка рейтинга. */
function TournamentDialog({ tournament, leaders, loading, error, onClose }: {
  tournament?: Tournament;
  leaders?: LeaderRow[];
  loading: boolean;
  error: boolean;
  onClose: () => void;
}) {
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  return <div className="modal-backdrop" onClick={onClose}>
    <div className="modal-card tournament-dialog" role="dialog" aria-modal="true" aria-labelledby="tournament-dialog-title" onClick={(event) => event.stopPropagation()}>
      <div className="modal-head">
        <div><span className="eyebrow">ТУРНИР</span><h2 id="tournament-dialog-title">Кубок воздухоплавателей</h2></div>
        <button className="modal-close" onClick={onClose} aria-label="Закрыть">×</button>
      </div>
      {tournament?.active
        ? <p className="muted">До окончания: <b>{tournamentRemaining(tournament.endsAt)}</b> · призы {number(tournament.firstPrize)} / {number(tournament.secondPrize)} / {number(tournament.thirdPrize)} ★</p>
        : tournament ? <p className="muted">Турнир завершён{tournament.prizesPaid ? ', призы выплачены.' : '.'}</p>
        : <p className="muted">Синхронизируем данные турнира…</p>}
      {loading ? <div className="empty"><div className="spinner" /></div>
        : error ? <div className="state-error"><span>!</span><b>Не удалось получить рейтинг</b><p className="muted">Откройте полный рейтинг чуть позже.</p></div>
        : leaders?.length ? <ol className="tournament-list">
          {leaders.slice(0, 10).map((row, index) => <li key={row.id} className={index < 3 ? 'top' : ''}>
            {index < 3 ? <img src={`/assets/medal-${index + 1}.png`} alt={`${index + 1} место`} width={26} height={26} /> : <b className="num">#{index + 1}</b>}
            <span>{row.username}</span>
            <strong className="num">{number(row.points)} ★</strong>
          </li>)}
        </ol>
        : <div className="empty"><b>Пока нет участников турнира</b></div>}
      <div className="start-row">
        <span className="muted">Очки начисляются за каждый пройденный уровень</span>
        <Link className="primary" to="/leaderboard" onClick={onClose}>Полный рейтинг <span aria-hidden="true">→</span></Link>
      </div>
    </div>
  </div>;
}

export function Play() {
  const nav = useNavigate();
  const client = useQueryClient();
  const { theme, setTheme } = useStore();
  const [bet, setBet] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [rulesOpen, setRulesOpen] = useState(false);
  const [tournamentOpen, setTournamentOpen] = useState(false);
  const playDropSound = () => {
    const sound = new Audio('/assets/drop-click.mp3');
    sound.volume = 0.7;
    void sound.play().catch(() => {});
  };
  const { data: me } = useQuery({ queryKey: ['me'], queryFn: api.me });
  const { data: config } = useQuery({ queryKey: ['config', theme], queryFn: () => api.config(theme) });
  const { data: red } = useQuery({ queryKey: ['config', 'RED'], queryFn: () => api.config('RED') });
  const { data: green } = useQuery({ queryKey: ['config', 'GREEN'], queryFn: () => api.config('GREEN') });
  const { data: tournament } = useQuery({ queryKey: ['tournament'], queryFn: api.tournament, refetchInterval: 60000 });
  const leadersQuery = useQuery({ queryKey: ['leaderboard'], queryFn: api.leaderboard, enabled: tournamentOpen, retry: false });

  const options = config?.betOptions || [];
  const selected = options.find((option) => option.id === bet);
  const balance = me?.balance ?? 0;
  const affordable = (amount: number) => amount <= balance;

  const chooseBet = (optionId: string, amount: number) => {
    if (!affordable(amount)) {
      setBet('');
      setError('Не хватает бонусов');
      return;
    }
    setError('');
    setBet(optionId);
  };
  const levels = config?.levels ?? 12;
  const ladder = config?.levelTable || [];
  const maxMultiplier = config?.maxMultiplier;

  const start = async () => {
    if (!bet || busy) return;
    setBusy(true);
    setError('');
    try {
      const round = await api.start(theme, bet);
      client.setQueryData(['round', round.roundId], round);
      client.invalidateQueries({ queryKey: ['me'] });
      nav(`/round/${round.roundId}`);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Не удалось запустить раунд');
    } finally {
      setBusy(false);
    }
  };

  return <div className="play-layout design-play">
    <section className="weekly-bonus">
      <h2>Ежедневный бонус</h2>
      <div className="bonus-days">
        {['✓', '20', '30', '2', '40', '2', ''].map((reward, index) => <div className={`bonus-day ${index === 1 ? 'today' : ''}`} key={index}>
          <b>День {index + 1}</b><span>{index === 6 ? <img className="surprise-icon" src="/assets/secret-box.png" alt="Сюрприз" /> : <>{reward}{index > 0 && index !== 3 && index !== 5 ? <img src="/assets/coin.png" alt="" /> : index === 3 || index === 5 ? <img src="/assets/puzzle-piece.png" alt="" /> : null}</>}</span>
        </div>)}
      </div>
    </section>

    <section className="setup-card">
      <div className="section-title">
        <div><h2>Выберите тему</h2></div>
        <button className="rules-link" onClick={() => setRulesOpen(true)}>ⓘ Правила игры</button>
      </div>
      <div className="theme-grid">
        <button className={`theme-option ${theme === 'RED' ? 'selected' : ''}`} onClick={() => { playDropSound(); setTheme('RED'); setBet(''); }}>
          <img src="/assets/balloon-red.png" alt="" />
          <span><b>Красный шар</b><small>{red?.levels ?? '—'} уровней полёта</small><small>Максимальный коэффициент<br />×{(red?.maxMultiplier ?? 0).toFixed(2)}</small><small>Вероятность «краха» выше,<br />но и максимальный выигрыш</small></span>
        </button>
        <button className={`theme-option ${theme === 'GREEN' ? 'selected' : ''}`} onClick={() => { playDropSound(); setTheme('GREEN'); setBet(''); }}>
          <img src="/assets/balloon-green.png" alt="" />
          <span><b>Зелёный шар</b><small>{green?.levels ?? '—'} уровней полёта</small><small>Максимальный коэффициент<br />×{(green?.maxMultiplier ?? 0).toFixed(2)}</small><small>Шансы забрать выигрыш<br />до «краха» выше</small></span>
        </button>
      </div>

      <div className="section-title bet-title">
        <div><h2>Выберите ставку</h2></div>
        <div className="section-actions">
          <button className="text-button tournament-trigger" onClick={() => setTournamentOpen(true)}><img src="/assets/medal-1.png" alt="" /> {tournament?.active === false ? 'Рейтинг' : 'Турнир'}</button>
        </div>
      </div>

      <div className="bet-grid">
        {options.map((option, index) => {
          const locked = !affordable(option.amount);
          return <button
            key={option.id}
            className={`bet-option ${bet === option.id ? 'selected' : ''} ${locked ? 'locked' : ''}`}
            aria-disabled={locked}
            title={locked ? 'Не хватает бонусов' : `Ставка ${option.amount}, бустер ×${option.boosterMultiplier}`}
            onClick={() => chooseBet(option.id, option.amount)}
          >
            <img src={BET_ART[index] || BET_ART[BET_ART.length - 1]} alt="" />
            <b>{option.amount} <img src="/assets/coin.png" alt="" width={20} height={20} /></b>
            <small>бонусов</small>
            <strong>бустер ×{option.boosterMultiplier}</strong>
            {option.boosterMultiplier > 1 && option.boosterChance !== undefined && <em className="bet-chance">выпадает в {Math.round(option.boosterChance * 100)}% раундов</em>}
            {locked && <em className="bet-locked">НЕ ХВАТАЕТ</em>}
          </button>;
        })}
      </div>

      <div className="design-start-row">
        <div>Максимально возможный выигрыш с этой ставкой <b>{selected ? number(selected.amount * (maxMultiplier ?? 0)) : '—'} <img src="/assets/coin.png" alt="" /></b></div>
        <button className={`design-start ${busy ? 'is-loading' : ''}`} disabled={!bet || busy} onClick={start}>▶&nbsp; {busy ? 'Запускаем…' : 'Начать игру!'}</button>
      </div>

      {ladder.length > 0 && <div className="admin-group">
        <div className="section-title">
          <div><span className="eyebrow">ЛЕСТНИЦА КОЭФФИЦИЕНТОВ</span></div>
          <span className="muted">{selected && selected.boosterMultiplier > 1
            ? `со ставкой ×${selected.boosterMultiplier} коэффициенты ниже, зато на уровне бустера умножаются — возврат игроку одинаков`
            : 'коэффициент растёт экспоненциально, риск взрыва — вместе с ним'}</span>
        </div>
        <div className="ladder-strip">
          {ladder.map((row, index) => <div
            key={row.level}
            className={`ladder-step ${row.popChance >= 0.3 ? 'risk-high' : row.popChance >= 0.2 ? 'risk-mid' : ''}`}
          >
            <b>×{(selected?.ladder?.[index] ?? row.multiplier).toFixed(2)}</b>
            <small>ур. {row.level}</small>
            <i>{Math.round(row.popChance * 100)}%</i>
          </div>)}
        </div>
      </div>}

      <div className="start-row">
        <div className="selection-summary">
          {selected ? <><span className="eyebrow">ВЫБРАНО</span><b className="num">{selected.amount} ★</b></> : <span className="muted">Ставка не выбрана</span>}
          {error && <div className="form-error">{error}</div>}
        </div>
        <button className="primary" disabled={!bet || busy} onClick={start}>
          {busy ? 'Подготовка…' : `Запустить полёт${selected ? ` · ${selected.amount} ★` : ''}`} <span aria-hidden="true">→</span>
        </button>
      </div>
    </section>

    {rulesOpen && <RulesDialog onClose={() => setRulesOpen(false)} />}
    {tournamentOpen && <TournamentDialog tournament={tournament} leaders={leadersQuery.data} loading={leadersQuery.isLoading} error={leadersQuery.isError} onClose={() => setTournamentOpen(false)} />}
  </div>;
}
