import { useEffect, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { api, type LotteryOffer } from '../../shared/api/client';

/**
 * «Закрепи успех» по п. 1.8: показывается один раз за сессию после выигрышного раунда
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
        <p className="muted">{offer.tickets} лотерейных билетов начислено на счёт. Посмотреть их можно во вкладке «Лотерея».</p>
        <button className="primary cta" onClick={onClose}>Отлично <span>→</span></button>
      </> : <>
        <h2>Вы выиграли {offer.payout} ★</h2>
        <p className="muted">Закрепите результат: обменяйте часть выигрыша на лотерейные билеты со скидкой.</p>
        <div className="upsell-offer">
          <span className="ticket-icon">🎟</span>
          <div><b>{offer.tickets} билетов</b><small>вместо {offer.fullPrice} ★</small></div>
          <div className="upsell-price">{offer.price} ★</div>
        </div>
        {error && <div className="form-error">{error}</div>}
        <button className="primary cta" disabled={busy} onClick={accept}>
          {busy ? 'Покупаем…' : `Купить за ${offer.price} ★`} <span>→</span>
        </button>
        <button className="text-link upsell-skip" onClick={onClose}>Не сейчас · окно закроется через {secondsLeft} сек</button>
      </>}
    </div>
  </div>;
}
