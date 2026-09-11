import { useQuery } from '@tanstack/react-query';
import { api, type Round } from '../../shared/api/client';

const formatDate = (value?: string) =>
  new Date(value || Date.now()).toLocaleString('ru-RU', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' });
const profitOf = (round: Round) => round.profit ?? (round.payout || 0) - round.betAmount;
const finalMultiplier = (round: Round) => round.cashedOutMultiplier || round.multiplier || 1;

export function History() {
  const { data = [], isLoading } = useQuery({ queryKey: ['history'], queryFn: api.history, refetchInterval: 5000, refetchIntervalInBackground: true });
  const wins = data.filter((round) => (round.payout || 0) > 0);
  const total = data.reduce((sum, round) => sum + profitOf(round), 0);
  const best = data.reduce((max, round) => ((round.payout || 0) > 0 ? Math.max(max, finalMultiplier(round)) : max), 0);

  return <section>
    <div className="section-title page-title">
      <div><span className="eyebrow">ВАША СТАТИСТИКА</span><h1>История раундов</h1></div>
      <span className="muted">Последние 50 полётов</span>
    </div>

    <div className="history-summary">
      <div><small>РАУНДОВ</small><b>{data.length}</b></div>
      <div><small>ВЫИГРЫШЕЙ</small><b>{wins.length}{data.length ? ` · ${Math.round((wins.length / data.length) * 100)}%` : ''}</b></div>
      <div><small>ЛУЧШИЙ КОЭФФИЦИЕНТ</small><b>{best ? `×${best.toFixed(2)}` : '—'}</b></div>
      <div><small>ИТОГ</small><b className={total >= 0 ? 'win' : 'crash'}>{total >= 0 ? '+' : ''}{total} ★</b></div>
    </div>

    {isLoading ? <div className="card loading">Загрузка истории…</div> : <div className="table history-table">
      <div className="row table-head"><span>Дата</span><span>Тема</span><span>Ставка</span><span>Уровень</span><span>Коэффициент</span><span>Итог</span><span>Очки</span></div>
      {data.map((round) => {
        const profit = profitOf(round);
        const won = (round.payout || 0) > 0;
        return <div className="row" key={round.roundId}>
          <span>{formatDate(round.createdAt)}</span>
          <span><i className={`dot ${String(round.theme).toLowerCase()}`} /> {round.theme === 'RED' ? 'Красная' : 'Зелёная'}</span>
          <span>{round.betAmount} ★</span>
          <span>{round.level} / {round.levels}</span>
          <span className={won ? 'win' : 'crash'}>{won ? `×${finalMultiplier(round).toFixed(2)}` : `Взрыв на ${round.popLevel ?? round.level + 1}`}</span>
          <span className={profit >= 0 ? 'win' : 'crash'}>{profit >= 0 ? '+' : ''}{profit} ★</span>
          <b>{round.points || 0} ★</b>
        </div>;
      })}
      {!data.length && <div className="empty"><span>🎈</span><p>История пока пуста</p><small>Сыграйте первый раунд, и он появится здесь</small></div>}
    </div>}
  </section>;
}
