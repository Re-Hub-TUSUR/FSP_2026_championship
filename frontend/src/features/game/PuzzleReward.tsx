import type { PuzzleAward } from '../../shared/api/client';

const ART = ['puzzle-gold', 'puzzle-green', 'puzzle-red'];

export const fragmentArt = (index: number) => `/art/${ART[index % ART.length]}.svg`;

/** Награда за раунд: фрагмент пазла и прогресс коллекции. */
export function PuzzleReward({ reward }: { reward: PuzzleAward }) {
  return <div className={`puzzle-reward ${reward.completed ? 'completed' : ''}`}>
    <span className="eyebrow">НАГРАДА ЗА РАУНД</span>
    <div className="puzzle-reward-body">
      <img src={fragmentArt(reward.fragment)} alt={`Фрагмент ${reward.fragment + 1}`} />
      <div>
        <b>Фрагмент {reward.fragment + 1}</b>
        <small>{reward.completed
          ? `Пазл собран целиком — бонус ${reward.bonus} ★`
          : `Собрано ${reward.collected} из ${reward.total}`}</small>
      </div>
      <div className="puzzle-progress" aria-hidden="true">
        {Array.from({ length: reward.total }, (_, i) => <i key={i} className={reward.completed || i < reward.collected ? 'on' : ''} />)}
      </div>
    </div>
  </div>;
}
