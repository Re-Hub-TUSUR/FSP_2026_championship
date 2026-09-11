import { useEffect } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../shared/api/client';

export function Result() {
  const { id = '' } = useParams();
  const nav = useNavigate();
  const client = useQueryClient();
  const { data: round } = useQuery({ queryKey: ['round', id], queryFn: () => api.round(id) });

  useEffect(() => {
    if (!round) return;
    if (round.state === 'STARTED') {
      nav(`/round/${id}`, { replace: true });
      return;
    }
    client.invalidateQueries({ queryKey: ['me'] });
    client.invalidateQueries({ queryKey: ['history'] });
  }, [round?.state, id, nav, client]);

  if (!round || round.state === 'STARTED') return <div className="card loading"><div className="spinner" />Завершаем раунд…</div>;

  const won = (round.payout || 0) > 0;
  const profit = round.profit ?? (round.payout || 0) - round.betAmount;
  const reachedTop = round.state === 'SETTLED';

  return <section className={`card result-card ${won ? 'won' : 'lost'}`}>
    <div className="result-icon">{won ? '✦' : '💥'}</div>
    <span className="eyebrow">РАУНД ЗАВЕРШЁН</span>
    <h1>{won ? 'Поздравляем!' : 'Шар лопнул'}</h1>
    <p className="result-lead">
      {won
        ? reachedTop ? 'Шар долетел до вершины — выигрыш начислен автоматически' : 'Вы вовремя зафиксировали свой выигрыш'
        : `Шар лопнул на уровне ${round.popLevel ?? round.level + 1} из ${round.levels}`}
    </p>
    <div className="result-numbers">
      <div><small>КОЭФФИЦИЕНТ</small><b>{won ? `×${(round.cashedOutMultiplier || round.multiplier).toFixed(2)}` : '—'}</b></div>
      <div><small>ВЫПЛАТА</small><b>{round.payout || 0} <i>★</i></b></div>
      <div><small>ИТОГ РАУНДА</small><b className={profit >= 0 ? 'win' : 'crash'}>{profit >= 0 ? '+' : ''}{profit} <i>★</i></b></div>
      <div><small>ОЧКИ</small><b>{round.points || 0}</b></div>
    </div>
    {won && round.popLevel !== undefined && round.popLevel <= round.levels && <p className="muted">Шар лопнул бы на уровне {round.popLevel} — вы вышли вовремя</p>}
    <p className="muted result-balance">Баланс после раунда: <b>{round.balanceAfter ?? round.balance ?? 0} ★</b></p>
    <Link className="primary cta" to="/play">Новый полёт <span>→</span></Link>
    <Link className="text-link" to="/history">Посмотреть историю</Link>
  </section>;
}
