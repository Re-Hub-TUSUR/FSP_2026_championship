import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, type LeaderRow, type Tournament } from '../../shared/api/client';
import { useStore } from '../../store';
import { CrashHistory } from './CrashHistory';
import { RulesDialog } from './RulesDialog';

function tournamentRemaining(endsAt: string) {
  const end = new Date(endsAt).getTime();
  if (!Number.isFinite(end)) return '—';
  const remaining = Math.max(0, end - Date.now());
  const totalMinutes = Math.ceil(remaining / 60_000);
  const days = Math.floor(totalMinutes / 1440);
  const hours = Math.floor((totalMinutes % 1440) / 60);
  const minutes = totalMinutes % 60;
  return days > 0 ? `${days} дн. ${hours} ч.` : `${hours} ч. ${minutes} мин.`;
}

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
    <div className="modal-card tournament-modal" role="dialog" aria-modal="true" aria-labelledby="tournament-dialog-title" onClick={(event) => event.stopPropagation()}>
      <div className="modal-head">
        <div><span className="eyebrow">🏆 ТУРНИР</span><h2 id="tournament-dialog-title">Турнирная таблица</h2></div>
        <button className="modal-close" onClick={onClose} aria-label="Закрыть">×</button>
      </div>
      {tournament?.active ? <p className="muted">До окончания: <b>{tournamentRemaining(tournament.endsAt)}</b> · Призы: {tournament.firstPrize} / {tournament.secondPrize} / {tournament.thirdPrize} ★</p> : tournament ? <p className="muted">Турнир завершён{tournament.prizesPaid ? ', призы выплачены.' : '.'}</p> : <p className="muted">Синхронизируем данные турнира…</p>}
      {loading ? <div className="loading"><div className="spinner" />Загрузка рейтинга…</div> : error ? <p className="tournament-dialog-error">Не удалось получить рейтинг. Откройте полный рейтинг позже.</p> : leaders?.length ? <div className="tournament-list">{leaders.slice(0, 10).map((row, index) => <div className={`tournament-row ${index < 3 ? 'top' : ''}`} key={row.id}><b>#{index + 1}</b><span>{row.username}</span><strong>{row.points} ★</strong></div>)}</div> : <p className="tournament-dialog-empty">Пока нет участников турнира.</p>}
      <Link className="text-link" to="/leaderboard" onClick={onClose}>Открыть полный рейтинг →</Link>
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
  const { data: me } = useQuery({ queryKey: ['me'], queryFn: api.me });
  const { data: config } = useQuery({ queryKey: ['config', theme], queryFn: () => api.config(theme) });
  const { data: red } = useQuery({ queryKey: ['config', 'RED'], queryFn: () => api.config('RED') });
  const { data: green } = useQuery({ queryKey: ['config', 'GREEN'], queryFn: () => api.config('GREEN') });
  const { data: tournament } = useQuery({ queryKey: ['tournament'], queryFn: api.tournament, refetchInterval: 60000 });
  const leadersQuery = useQuery({ queryKey: ['leaderboard'], queryFn: api.leaderboard, enabled: tournamentOpen, retry: false });
  const { data: leaders } = leadersQuery;

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

  return <div className="play-layout">
    <section className="hero-card">
      <div className="hero-copy">
        <span className="eyebrow">100ЛОТО ПРЕДСТАВЛЯЕТ</span>
        <h1>Поймайте свой<br /><em>счастливый полёт!</em></h1>
        <p>Поднимайтесь выше, открывайте множители и забирайте выигрыш до того, как шар лопнет.</p>
        <div className="hero-stats">
          <span><b>{levels}</b><small>уровней</small></span>
          <span><b>×{(maxMultiplier ?? 0).toFixed(2)}</b><small>максимум</small></span>
          <span><b>{config?.rtp ? `${Math.round(config.rtp * 100)}%` : '24/7'}</b><small>{config?.rtp ? 'возврат игроку' : 'в игре'}</small></span>
        </div>
      </div>
      <div className="hero-sky">
        <div className="cloud c1" /><div className="cloud c2" />
        <div className="balloon"><div className="balloon-shine" /><div className="balloon-band" /><div className="balloon-rope" /><div className="basket">▥</div></div>
        <div className="sun">✦</div>
      </div>
    </section>

    <CrashHistory />

    <section className="card setup-card">
      <div className="section-title">
        <div><span className="eyebrow">ШАГ 1</span><h2>Выберите тему</h2></div>
        <span className="live-dot">● LIVE</span>
      </div>
      <div className="theme-grid">
        <button className={`theme-option red ${theme === 'RED' ? 'selected' : ''}`} onClick={() => { setTheme('RED'); setBet(''); }}>
          <span className="theme-icon">♦</span><span><b>Красный шар</b><small>{red?.levels ?? '—'} уровней · до ×{(red?.maxMultiplier ?? 0).toFixed(2)}</small></span><i>✓</i>
        </button>
        <button className={`theme-option green ${theme === 'GREEN' ? 'selected' : ''}`} onClick={() => { setTheme('GREEN'); setBet(''); }}>
          <span className="theme-icon">♣</span><span><b>Зелёный шар</b><small>{green?.levels ?? '—'} уровней · до ×{(green?.maxMultiplier ?? 0).toFixed(2)}</small></span><i>✓</i>
        </button>
      </div>

      <div className="section-title bet-title">
        <div><span className="eyebrow">ШАГ 2</span><h2>Выберите ставку</h2></div>
        <div><button className="link-button tournament-trigger" onClick={() => setTournamentOpen(true)}>🏆 {tournament?.active ? 'Турнир' : 'Рейтинг'}</button> <button className="link-button" onClick={() => setRulesOpen(true)}>? Правила игры</button></div>
      </div>
      <div className="bet-grid">
        {options.map((option) => {
          const locked = !affordable(option.amount);
          return <button
            key={option.id}
            className={`bet-option ${bet === option.id ? 'selected' : ''} ${locked ? 'locked' : ''}`}
            aria-disabled={locked}
            title={locked ? 'Не хватает бонусов' : `Ставка ${option.amount}, бустер ×${option.boosterMultiplier}`}
            onClick={() => chooseBet(option.id, option.amount)}
          >
            <span className="bet-star">★</span><b>{option.amount}</b><small>очков</small><strong>×{option.boosterMultiplier}</strong>
            {option.boosterMultiplier > 1 && option.boosterChance !== undefined && <em className="bet-chance">бустер в {Math.round(option.boosterChance * 100)}% раундов</em>}
            {locked && <em className="bet-locked">не хватает</em>}
          </button>;
        })}
      </div>

      {ladder.length > 0 && <div className="ladder">
        <div className="ladder-head">
          <span className="eyebrow">ЛЕСТНИЦА КОЭФФИЦИЕНТОВ</span>
          <span className="muted">{selected && selected.boosterMultiplier > 1
            ? `со ставкой ×${selected.boosterMultiplier} коэффициенты ниже, зато на уровне бустера умножаются — возврат игроку одинаков`
            : 'коэффициент растёт экспоненциально, риск взрыва — вместе с ним'}</span>
        </div>
        <div className="ladder-strip">
          {ladder.map((row, index) => <div key={row.level} className="ladder-step">
            <b>×{(selected?.ladder?.[index] ?? row.multiplier).toFixed(2)}</b>
            <small>ур. {row.level}</small>
            <i>{Math.round(row.popChance * 100)}%</i>
          </div>)}
        </div>
      </div>}

      <div className="start-row">
        <div className="selection-summary">
          {selected ? <><span>Ваша ставка</span><b>{selected.amount} <i>★</i></b></> : <span className="muted">Ставка не выбрана</span>}
          {error && <small className="error">{error}</small>}
        </div>
        <button className="primary cta" disabled={!bet || busy} onClick={start}>{busy ? 'Подготовка…' : 'Запустить полёт'} <span>→</span></button>
      </div>
    </section>

    {rulesOpen && <RulesDialog onClose={() => setRulesOpen(false)} />}
    {tournamentOpen && <TournamentDialog tournament={tournament} leaders={leaders} loading={leadersQuery.isLoading} error={leadersQuery.isError} onClose={() => setTournamentOpen(false)} />}
  </div>;
}
