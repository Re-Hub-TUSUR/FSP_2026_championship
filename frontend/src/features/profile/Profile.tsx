import { useQuery } from '@tanstack/react-query';
import { api } from '../../shared/api/client';
import { fragmentArt } from '../game/PuzzleReward';

export function Profile() {
  const { data, isLoading } = useQuery({ queryKey: ['me'], queryFn: api.me });
  if (isLoading || !data) return <div className="card loading"><div className="spinner" />Загрузка профиля…</div>;

  const total = data.puzzleFragments ?? 6;
  const mask = data.puzzleMask ?? 0;
  const collected = Array.from({ length: total }, (_, i) => (mask & (1 << i)) !== 0);
  const collectedCount = collected.filter(Boolean).length;

  return <section className="profile-grid">
    <div className="card profile-hero">
      <div className="profile-avatar">{data.username.slice(0, 1).toUpperCase()}</div>
      <div>
        <span className="eyebrow">ПРОФИЛЬ ИГРОКА</span>
        <h1>{data.username}</h1>
        <p className="muted">{data.role === 'ADMIN' ? 'Администратор платформы' : 'Пилот воздушного шара'}</p>
      </div>
      <span className="status-badge"><i /> Активен</span>
    </div>

    <div className="card stat-card"><small>ТЕКУЩИЙ БАЛАНС</small><strong>{data.balance.toLocaleString('ru-RU')} <i>★</i></strong></div>
    <div className="card stat-card"><small>ОЧКИ</small><strong>{(data.points ?? 0).toLocaleString('ru-RU')}</strong><span className="muted">уровень {data.level || 1}</span></div>
    <div className="card stat-card"><small>СОБРАНО ПАЗЛОВ</small><strong>{data.puzzlesCompleted ?? 0}</strong><span className="muted">бонус {data.puzzleBonus ?? 250} ★ за каждый</span></div>

    <div className="card puzzle-card">
      <div className="section-title">
        <div><span className="eyebrow">КОЛЛЕКЦИЯ</span><h2>Текущий пазл</h2></div>
        <span className="muted">{collectedCount} из {total}</span>
      </div>
      <p className="muted">За каждый завершённый раунд выдаётся случайный недостающий фрагмент. Соберите все — и получите {data.puzzleBonus ?? 250} бонусных баллов.</p>
      <div className="puzzle-collection">
        {collected.map((has, index) => <div key={index} className={`puzzle-slot ${has ? 'has' : ''}`}>
          <img src={fragmentArt(index)} alt={`Фрагмент ${index + 1}`} />
          <small>{index + 1}</small>
        </div>)}
      </div>
    </div>
  </section>;
}
