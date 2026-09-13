import { useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../shared/api/client';

/** Арт пака растёт вместе с количеством билетов. */
const PACK_ART = (tickets: number) => tickets >= 10 ? '/assets/lucky-box.png' : '/assets/ticket.png';
const number = (value: number) => Math.round(value).toLocaleString('ru-RU').replace(/ /g, ' ');

/** Ближайший тираж — сегодня в 20:00, иначе завтра. */
function useDrawCountdown() {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);
  const draw = new Date();
  draw.setHours(20, 0, 0, 0);
  if (draw.getTime() < now) draw.setDate(draw.getDate() + 1);
  const left = Math.max(0, Math.floor((draw.getTime() - now) / 1000));
  return {
    hours: Math.floor(left / 3600),
    minutes: Math.floor((left % 3600) / 60),
    seconds: left % 60,
    label: draw.toLocaleString('ru-RU', { day: 'numeric', month: 'long', hour: '2-digit', minute: '2-digit' })
  };
}

export function Lottery() {
  const client = useQueryClient();
  const { data, isLoading, isError, refetch } = useQuery({ queryKey: ['lottery'], queryFn: api.lottery });
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const [revealed, setRevealed] = useState<Array<{ key: number; prize: number }>>([]);
  const [scratchingIndex, setScratchingIndex] = useState<number | null>(null);
  const clock = useDrawCountdown();

  if (isLoading) return <div className="card"><div className="spinner" /></div>;
  if (isError || !data) return <div className="state-error">
    <span>!</span>
    <b>Лотерея временно недоступна</b>
    <p className="muted">Не удалось получить каталог билетов с сервера.</p>
    <button className="ghost" onClick={() => refetch()}>Повторить</button>
  </div>;

  const buy = async (packId: string, tickets: number) => {
    setBusy(packId); setError(''); setMessage('');
    try {
      await api.buyTickets(packId);
      await refetch();
      client.invalidateQueries({ queryKey: ['me'] });
      setMessage(`Начислено билетов: ${tickets}`);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Не удалось купить билеты');
    } finally {
      setBusy('');
    }
  };

  // Стирание идёт на сервере: билет списывается, приз начисляется на баланс одной транзакцией.
  const scratch = async (index: number) => {
    if (scratchingIndex !== null) return;
    setScratchingIndex(index); setError(''); setMessage('');
    try {
      const result = await api.scratchTicket();
      setRevealed((items) => [{ key: Date.now(), prize: result.prize }, ...items].slice(0, 12));
      setMessage(`Билет стёрт: +${result.prize} монет на баланс`);
      await refetch();
      client.invalidateQueries({ queryKey: ['me'] });
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Не удалось стереть билет');
    } finally {
      setScratchingIndex(null);
    }
  };

  const ticketCount = data.tickets;
  const visibleTickets = Math.min(ticketCount, 12);
  const prizes = data.scratchPrizes ?? [];
  const prizeRange = prizes.length ? `от ${Math.min(...prizes.map((p) => p.amount))} до ${Math.max(...prizes.map((p) => p.amount))}` : '';
  const pad = (value: number) => String(value).padStart(2, '0');

  return <div className="play-layout">
    <div className="section-title">
      <div>
        <span className="eyebrow">ЗАКРЕПИ УСПЕХ</span>
        <h1>Лотерея Столото</h1>
        <p className="muted">Обменяйте бонусы на билеты и участвуйте в ближайшем розыгрыше.</p>
      </div>
      <div className="ticket-counter">
        <img src="/assets/ticket.png" alt="" width={40} height={40} />
        <span><small className="eyebrow">ВАШИ БИЛЕТЫ</small><b>{ticketCount}</b></span>
      </div>
    </div>

    <section className="draw-banner">
      <img src="/assets/lucky-box.png" alt="" width={72} height={72} />
      <div>
        <h2>Ближайший розыгрыш — сегодня в 20:00</h2>
        <p>Главный приз <span className="coin-value">100 000 <img src="/assets/coin.png" alt="монет" /></span> · Тираж {clock.label}</p>
      </div>
      <div className="draw-timer">
        <span><b>{pad(clock.hours)}</b><small>часы</small></span>
        <span><b>{pad(clock.minutes)}</b><small>минуты</small></span>
        <span><b>{pad(clock.seconds)}</b><small>секунды</small></span>
      </div>
    </section>

    <section className="ticket-grid">
      {data.packs.map((pack) => {
        const price = Math.round(pack.price);
        const perTicket = Math.round(price / pack.tickets);
        const saving = Math.round((1 - price / (pack.tickets * data.ticketPrice)) * 100);
        const affordable = price <= data.balance;
        return <div key={pack.id} className={`ticket-pack ${pack.tickets === 10 ? 'featured' : ''} ${affordable ? '' : 'locked'}`}>
          {pack.tickets === 10 && <span className="badge hit">ХИТ · ВЫГОДНО</span>}
          {saving > 0 && pack.tickets !== 10 && <span className="badge save">−{saving}%</span>}
          <img src={PACK_ART(pack.tickets)} alt="" width={78} height={78} />
          <b className="num" style={{ fontSize: 26 }}>{pack.tickets}</b>
          <small className="muted">{pack.label}</small>
          <div className="ticket-price">
            {price} <img src="/assets/coin.png" alt="" width={18} height={18} />
            <i className="coin-value">{perTicket} <img src="/assets/coin.png" alt="монет" /> за билет</i>
          </div>
          <button className={`primary ${busy === pack.id ? 'is-loading' : ''}`} disabled={!affordable || busy === pack.id} onClick={() => buy(pack.id, pack.tickets)}>
            {busy === pack.id ? 'Покупаем…' : affordable ? 'Купить билеты' : 'Не хватает бонусов'}
          </button>
        </div>;
      })}
    </section>

    {error && <div className="form-error">{error}</div>}
    {message && <div className="state-funds">{message}</div>}

    <section className="card">
      <div className="section-title">
        <div>
          <span className="eyebrow">ВАША КОЛЛЕКЦИЯ</span>
          <h2>Мои билеты <small className="muted">({ticketCount} шт.)</small></h2>
        </div>
        <span className="muted">Нажмите на билет, чтобы стереть защитный слой</span>
      </div>

      {visibleTickets || revealed.length ? <div className="puzzle-collection">
        {Array.from({ length: visibleTickets }, (_, index) => <button
          key={`ticket-${index}`}
          type="button"
          className={`scratch ${scratchingIndex === index ? 'scratching' : ''}`}
          disabled={scratchingIndex !== null}
          onClick={() => scratch(index)}
        >
          <b>{scratchingIndex === index ? 'Стираем…' : 'Стереть'}</b>
          <small className="muted">№ {String(index + 1).padStart(3, '0')}</small>
        </button>)}
        {revealed.map((item) => <div key={item.key} className="scratch revealed">
          <b className="coin-value">+{item.prize} <img src="/assets/coin.png" alt="монет" /></b>
          <small className="muted">начислено</small>
        </div>)}
      </div> : <div className="empty">
        <span>🎟</span>
        <b>Здесь пока пусто</b>
        <p className="muted">Купите первый билет, и он появится здесь.</p>
      </div>}
    </section>

    <section className="card">
      <span className="eyebrow">КАК ЭТО РАБОТАЕТ</span>
      <p className="muted">
        Покупка списывает бонусные баллы и добавляет билеты на счёт. Стёртый билет сразу приносит приз {prizeRange}:
        он разыгрывается и начисляется на сервере, а сам билет списывается.
        Цена одного билета — <span className="coin-value">{number(data.ticketPrice)} <img src="/assets/coin.png" alt="монет" /></span>, наборы дешевле поштучной покупки.
      </p>
    </section>
  </div>;
}
