import { useEffect, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { api, type LotteryOffer } from '../../shared/api/client';

/** 1 билет, 3 билета, 5 билетов. */
const ticketsWord = (count: number) => {
  const mod10 = count % 10;
  const mod100 = count % 100;
  if (mod10 === 1 && mod100 !== 11) return 'билет';
  if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return 'билета';
  return 'билетов';
};

/**
 * «Закрепи успех» по п. 1.8: приходит раз в 3 выигрыша
 * и закрывается сам через popupTimeout секунд, если игрок не взаимодействует с окном.
 */
export function UpsellDialog({ offer, roundId, onClose }: { offer: LotteryOffer; roundId: string; onClose: () => void }) {
  const client = useQueryClient();
  const [secondsLeft, setSecondsLeft] = useState(offer.timeoutSeconds || 10);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    if (busy || done) return;
    if (secondsLeft <= 0) { onClose(); return; }
    const timer = setTimeout(() => setSecondsLeft((value) => value - 1), 1000);
    return () => clearTimeout(timer);
  }, [secondsLeft, busy, done, onClose]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const accept = async () => {
    setBusy(true);
    setError('');
    try {
      await api.acceptOffer(roundId);
      client.invalidateQueries({ queryKey: ['me'] });
      client.invalidateQueries({ queryKey: ['lottery'] });
      setDone(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Не удалось купить билеты');
    } finally {
      setBusy(false);
    }
  };

  return <div className="modal-backdrop" onClick={onClose}>
    <div className="modal-card upsell-card" role="dialog" aria-modal="true" aria-label="Закрепи успех" onClick={(event) => event.stopPropagation()}>
      <button className="modal-close" onClick={onClose} aria-label="Закрыть">×</button>
      <span className="eyebrow">ЗАКРЕПИ УСПЕХ</span>

      {done ? <>
        <h2>Билеты у вас!</h2>
        <p className="muted">{offer.tickets} {ticketsWord(offer.tickets)} уже на счёте — посмотреть можно во вкладке «Лотерея».</p>
        <img src="/assets/lucky-box.png" alt="" width={120} height={120} style={{ margin: '10px auto' }} />
        <button className="primary" onClick={onClose}>Отлично <span aria-hidden="true">→</span></button>
      </> : <>
        <h2>Вы выиграли <span className="coin-value">{offer.payout} <img src="/assets/coin.png" alt="" /></span></h2>
        <p className="muted">Закрепите результат: лотерейные билеты со скидкой доступны только в этом окне.</p>

        <div className="upsell-offer">
          <img src="/assets/ticket.png" alt="" width={54} height={54} />
          <div>
            <b>{offer.tickets} {ticketsWord(offer.tickets)}</b>
            <small className="muted">вместо <s className="coin-value">{offer.fullPrice} <img src="/assets/coin.png" alt="" /></s></small>
          </div>
          <div className="upsell-price coin-value">{offer.price} <img src="/assets/coin.png" alt="" /></div>
        </div>

        {error && <div className="form-error">{error}</div>}

        <button className={`primary ${busy ? 'is-loading' : ''}`} disabled={busy} onClick={accept} style={{ width: '100%', marginTop: 16 }}>
          {busy ? 'Покупаем…' : <span className="coin-value">Купить за {offer.price} <img src="/assets/coin.png" alt="" /></span>} <span aria-hidden="true">→</span>
        </button>
        <button className="upsell-skip" onClick={onClose}>Не сейчас · окно закроется через {secondsLeft} с</button>
      </>}
    </div>
  </div>;
}
