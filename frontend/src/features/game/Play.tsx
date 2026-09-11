import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../shared/api/client';
import { useStore } from '../../store';

export function Play() {
  const nav = useNavigate();
  const client = useQueryClient();
  const { theme, setTheme } = useStore();
  const [bet, setBet] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const { data: config } = useQuery({ queryKey: ['config', theme], queryFn: () => api.config(theme) });

  const options = config?.betOptions || [];
  const selected = options.find((option) => option.id === bet);
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

    <section className="card setup-card">
      <div className="section-title">
        <div><span className="eyebrow">ШАГ 1</span><h2>Выберите тему</h2></div>
        <span className="live-dot">● LIVE</span>
      </div>
      <div className="theme-grid">
        <button className={`theme-option red ${theme === 'RED' ? 'selected' : ''}`} onClick={() => { setTheme('RED'); setBet(''); }}>
          <span className="theme-icon">♦</span><span><b>Красный шар</b><small>12 уровней · яркий полёт</small></span><i>✓</i>
        </button>
        <button className={`theme-option green ${theme === 'GREEN' ? 'selected' : ''}`} onClick={() => { setTheme('GREEN'); setBet(''); }}>
          <span className="theme-icon">♣</span><span><b>Зелёный шар</b><small>9 уровней · быстрый взлёт</small></span><i>✓</i>
        </button>
      </div>

      <div className="section-title bet-title">
        <div><span className="eyebrow">ШАГ 2</span><h2>Выберите ставку</h2></div>
        <span className="muted">Бустер умножает бонусные очки</span>
      </div>
      <div className="bet-grid">
        {options.map((option) => <button key={option.id} className={`bet-option ${bet === option.id ? 'selected' : ''}`} onClick={() => setBet(option.id)}>
          <span className="bet-star">★</span><b>{option.amount}</b><small>очков</small><strong>×{option.boosterMultiplier}</strong>
        </button>)}
      </div>

      {ladder.length > 0 && <div className="ladder">
        <div className="ladder-head"><span className="eyebrow">ЛЕСТНИЦА КОЭФФИЦИЕНТОВ</span><span className="muted">коэффициент растёт экспоненциально, риск взрыва — вместе с ним</span></div>
        <div className="ladder-strip">
          {ladder.map((row) => <div key={row.level} className="ladder-step">
            <b>×{row.multiplier.toFixed(2)}</b>
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
  </div>;
}
