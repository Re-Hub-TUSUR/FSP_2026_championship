import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { api } from '../../shared/api/client';

const number = (value: number) => Math.round(value).toLocaleString('ru-RU').replace(/ /g, ' ');

/** Компактный рейтинг на экране полёта: пять лучших игроков турнира. */
export function Top5Panel() {
  const { data = [] } = useQuery({
    queryKey: ['leaderboard'],
    queryFn: api.leaderboard,
    refetchInterval: 15000,
    staleTime: 10000
  });

  const top = data.slice(0, 5);
  if (!top.length) return null;

  return <aside className="top5-panel">
    <header>
      <img src="/assets/laurel-left.png" alt="" />
      <b>ТОП 5 ИГРОКОВ</b>
      <img src="/assets/laurel-right.png" alt="" />
    </header>
    <ol>
      {top.map((row, index) => <li key={row.id || row.username}>
        <img src={`/assets/medal-${index + 1}.png`} alt={`${index + 1} место`} />
        <span>
          <b>{number(row.points)} <img src="/assets/coin.png" alt="" width={16} height={16} /></b>
          <small>{row.username}</small>
        </span>
      </li>)}
    </ol>
    <Link className="text-button" to="/leaderboard">Вся турнирная таблица →</Link>
  </aside>;
}
