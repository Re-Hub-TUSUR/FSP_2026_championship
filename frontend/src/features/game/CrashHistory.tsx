import { useQuery } from '@tanstack/react-query';
import { api, type CrashRow } from '../../shared/api/client';

/** Порог, с которого коэффициент считается крупным и подсвечивается. */
const HIGH_MULTIPLIER = 4;

const tone = (row: CrashRow) => {
  if (row.survived) return 'top';
  if (row.crashMultiplier >= HIGH_MULTIPLIER) return 'high';
  if (row.crashMultiplier < 1.5) return 'low';
  return '';
};

/** Лента «Прошлые игры»: коэффициенты, на которых лопнули шары в завершённых раундах всех игроков. */
export function CrashHistory({ onDark = false }: { onDark?: boolean }) {
  const { data = [] } = useQuery({
    queryKey: ['crashes'],
    queryFn: api.crashes,
    refetchInterval: 4000,
    refetchIntervalInBackground: true
  });

  if (!data.length) return null;

  return <div className={`crash-history ${onDark ? 'on-dark' : ''}`}>
    <span className="crash-history-title">ПРОШЛЫЕ ИГРЫ</span>
    <div className="crash-history-strip">
      {data.map((row) => <span
        key={row.roundId}
        className={`crash-chip ${tone(row)}`}
        title={row.survived ? 'Шар долетел до вершины' : `Шар лопнул на ×${row.crashMultiplier.toFixed(2)}`}
      >×{row.crashMultiplier.toFixed(2)}</span>)}
    </div>
  </div>;
}
