import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../shared/api/client';

export function Lottery() {
  const client = useQueryClient();
  const { data, isLoading, refetch } = useQuery({ queryKey: ['lottery'], queryFn: api.lottery });
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');

  if (isLoading || !data) return <div className="card loading"><div className="spinner" />Загрузка лотереи…</div>;

  const buy = async (packId: string, tickets: number) => {
    setBusy(packId);
    setError('');
    setMessage('');
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

  return <section>
    <div className="section-title page-title">
      <div>
        <span className="eyebrow">ЗАКРЕПИ УСПЕХ</span>
        <h1>Лотерейные билеты</h1>
        <p className="muted">Обменяйте бонусные баллы на билеты. Чем больше набор, тем выгоднее цена билета.</p>
      </div>
      <div className="ticket-counter"><b>{data.tickets}</b><small>ваших билетов</small></div>
    </div>

    <div className="ticket-grid">
      {data.packs.map((pack) => {
        const perTicket = Math.round((pack.price / pack.tickets) * 10) / 10;
        const saving = Math.round((1 - pack.price / (pack.tickets * data.ticketPrice)) * 100);
        const affordable = pack.price <= data.balance;
        return <div key={pack.id} className={`card ticket-pack ${affordable ? '' : 'locked'}`}>
          {saving > 0 && <span className="ticket-saving">−{saving}%</span>}
          <span className="ticket-icon">🎟</span>
          <b>{pack.tickets}</b>
          <small>{pack.label}</small>
          <div className="ticket-price">{pack.price} ★<i>{perTicket} ★ за билет</i></div>
          <button className="primary" disabled={!affordable || busy === pack.id} onClick={() => buy(pack.id, pack.tickets)}>
            {busy === pack.id ? 'Покупаем…' : affordable ? 'Купить' : 'Не хватает бонусов'}
          </button>
        </div>;
      })}
    </div>

    {error && <div className="form-error">{error}</div>}
    {message && <div className="form-success">{message}</div>}

    <div className="card ticket-note">
      <h3>Как это работает</h3>
      <p className="muted">
        Билеты — имитация лотереи в рамках прототипа: покупка списывает бонусные баллы и начисляет билеты на счёт.
        После удачного раунда с крупным выигрышем то же предложение приходит всплывающим окном со скидкой — это и есть
        модуль «Закрепи успех».
      </p>
    </div>
  </section>;
}
