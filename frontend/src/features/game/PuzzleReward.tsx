import type { PuzzleAward } from '../../shared/api/client';

/** Награда за раунд: фрагмент пазла и прогресс коллекции. */
export function PuzzleReward({ reward }: { reward: PuzzleAward }) {
  return <div className={`puzzle-reward ${reward.completed ? 'completed' : ''}`}>
    <img src="/assets/puzzle.png" alt={`Фрагмент ${reward.fragment + 1}`} width={48} height={48} />
    <div>
      <span className="eyebrow">НАГРАДА ЗА РАУНД</span>
      <b>Фрагмент {reward.fragment + 1}</b>
      <small className="muted">{reward.completed
        ? 'Пазл собран целиком — ежедневный билет готов'
        : `Собрано ${reward.collected} из ${reward.total}`}</small>
    </div>
    <div className="puzzle-progress" aria-hidden="true">
      {Array.from({ length: reward.total }, (_, i) => <i key={i} className={reward.completed || i < reward.collected ? 'on' : ''} />)}
    </div>
  </div>;
}
