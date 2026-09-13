import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, type LotteryOffer } from '../../shared/api/client';
import { PuzzleReward } from './PuzzleReward';
import { DailyBonusPanel } from './DailyBonusPanel';
import { UpsellDialog } from '../lottery/UpsellDialog';

/**
 * Частоту показа определяет сервер — предложение приходит на каждый 4-й выигрыш.
 * Клиент лишь не повторяет окно для одного и того же раунда при возврате на экран.
 */
const upsellKey = (roundId: string) => `balloon-upsell-${roundId}`;

/** Через столько секунд бездействия игрок возвращается к выбору ставки (п. 1.5 постановки). */
const AUTO_RETURN_SECONDS = 10;

const number = (value: number) => Math.round(value).toLocaleString('ru-RU').replace(/ /g, ' ');

export function Result() {
  const { id = '' } = useParams();
  const nav = useNavigate();
  const client = useQueryClient();
  const { data: round } = useQuery({ queryKey: ['round', id], queryFn: () => api.round(id) });
  const [secondsLeft, setSecondsLeft] = useState(AUTO_RETURN_SECONDS);
  const [autoReturn, setAutoReturn] = useState(true);
  const [upsell, setUpsell] = useState<LotteryOffer | null>(null);

  useEffect(() => {
    if (!round) return;
    if (round.state === 'STARTED') {
      nav(`/round/${id}`, { replace: true });
      return;
    }
    client.invalidateQueries({ queryKey: ['me'] });
    client.invalidateQueries({ queryKey: ['daily-bonus'] });
    client.invalidateQueries({ queryKey: ['history'] });
    client.invalidateQueries({ queryKey: ['crashes'] });
  }, [round?.state, id, nav, client]);

  // Любое действие игрока отменяет автоматический возврат.
  useEffect(() => {
    const cancel = () => setAutoReturn(false);
    window.addEventListener('pointerdown', cancel);
    window.addEventListener('keydown', cancel);
    return () => {
      window.removeEventListener('pointerdown', cancel);
      window.removeEventListener('keydown', cancel);
    };
  }, []);

  // Персональное предложение запрашивается только после выигрышного раунда.
  useEffect(() => {
    if (!round || round.state === 'STARTED' || !(round.payout && round.payout > 0)) return;
    if (sessionStorage.getItem(upsellKey(id))) return;
    let cancelled = false;
    api.lotteryOffer(id)
      .then((offer) => {
        if (cancelled || !offer.eligible) return;
        sessionStorage.setItem(upsellKey(id), '1');
        setUpsell(offer);
      })
      .catch(() => { /* предложение необязательно: молча пропускаем */ });
    return () => { cancelled = true; };
  }, [round?.state, round?.payout, id]);

  useEffect(() => {
    if (!round || round.state === 'STARTED' || !autoReturn || upsell) return;
    if (secondsLeft <= 0) {
      nav('/play', { replace: true });
      return;
    }
    const timer = setTimeout(() => setSecondsLeft((value) => value - 1), 1000);
    return () => clearTimeout(timer);
  }, [round?.state, autoReturn, secondsLeft, upsell, nav]);

  if (!round || round.state === 'STARTED') return <div className="card loading"><div className="spinner" />Завершаем раунд…</div>;

  const won = (round.payout || 0) > 0;
  const profit = round.profit ?? (round.payout || 0) - round.betAmount;
  const reachedTop = round.state === 'SETTLED';
  const potentialMax = round.potentialMax ?? 0;
  const missed = won && potentialMax > (round.payout || 0);

  return <section className={`result-card ${won ? 'won' : 'lost'}`}>
    <div className="result-icon">{won ? '✦' : '💥'}</div>
    <span className="eyebrow">РАУНД ЗАВЕРШЁН</span>
    <h1>{won ? 'Поздравляем!' : 'Шар лопнул'}</h1>
    <p className="result-lead">
      {won
        ? reachedTop ? 'Шар долетел до вершины — выигрыш начислен автоматически' : 'Вы вовремя зафиксировали свой выигрыш'
        : `Шар лопнул на коэффициенте ×${(round.crashMultiplier ?? round.multiplier).toFixed(2)} — ставка сгорела`}
    </p>

    <div className="result-numbers">
      <div><small>КОЭФФИЦИЕНТ</small><b>{won ? `×${(round.cashedOutMultiplier || round.multiplier).toFixed(2)}` : '—'}</b></div>
      <div><small>ВЫПЛАТА</small><b>{number(round.payout || 0)}</b></div>
      <div><small>ИТОГ РАУНДА</small><b className={profit >= 0 ? 'win' : 'crash'}>{profit >= 0 ? '+' : ''}{number(profit)}</b></div>
      <div><small>ОЧКИ</small><b>{round.points || 0}</b></div>
    </div>

    {round.reward && <PuzzleReward reward={round.reward} />}
    {round.reward?.ticketEligible && <DailyBonusPanel token={localStorage.getItem('balloon-token')} />}

    {potentialMax > 0 && <p className={`result-potential ${missed ? 'missed' : ''}`}>
      Могли выиграть: <b>{number(potentialMax)} ★</b> — шар лопнул на уровне {round.popLevel ?? round.level + 1} из {round.levels}
    </p>}

    <div className="result-balance">
      <span className="muted">Баланс после раунда</span>
      <b className="num">{number(round.balanceAfter ?? round.balance ?? 0)} ★</b>
    </div>

    <div className="start-row">
      <Link className="text-button" to="/history">Посмотреть историю</Link>
      <Link className="primary" to="/play">Играть снова <span aria-hidden="true">→</span></Link>
    </div>

    {autoReturn && !upsell && <div className="auto-return">
      Возврат к выбору ставки через {secondsLeft} с
      <span><i style={{ width: `${(secondsLeft / AUTO_RETURN_SECONDS) * 100}%` }} /></span>
    </div>}

    {upsell && <UpsellDialog offer={upsell} roundId={id} onClose={() => setUpsell(null)} />}
  </section>;
}
