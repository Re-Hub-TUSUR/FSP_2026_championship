import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../../shared/api/client';

/** Иллюстрация слайда: своя мини-сцена вместо гифки — легче весит и не мылится. */
const SCENES = ['bet', 'launch', 'climb', 'booster', 'cashout', 'crash', 'points', 'lottery'];

/** Правила игры, доступные прямо с экрана ставки — до подтверждения выбора. */
export function RulesDialog({ onClose }: { onClose: () => void }) {
  const { data, isLoading } = useQuery({ queryKey: ['rules'], queryFn: api.rules });
  const [step, setStep] = useState(0);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const sections = data?.sections || [];
  const total = sections.length;
  const current = sections[Math.min(step, Math.max(0, total - 1))];
  const last = step >= total - 1;

  return <div className="modal-backdrop" onClick={onClose}>
    <div className="modal-card" role="dialog" aria-modal="true" aria-label="Правила игры" onClick={(event) => event.stopPropagation()}>
      <div className="modal-head">
        <div>
          <span className="eyebrow">{total ? `ПРАВИЛА ИГРЫ · ШАГ ${step + 1} ИЗ ${total}` : 'ПРАВИЛА ИГРЫ'}</span>
          <h2>{current?.heading || data?.title || 'Правила игры'}</h2>
        </div>
        <button className="modal-close" onClick={onClose} aria-label="Закрыть">×</button>
      </div>

      {isLoading ? <div className="empty"><div className="spinner" />Загрузка правил…</div> : <>
        <p className="muted">{current?.text}</p>

        <div className={`modal-scene scene-${SCENES[step % SCENES.length]}`} aria-hidden="true">
          <img className="asset-balloon" src="/assets/balloon-red.png" alt="" />
        </div>

        <div className="start-row">
          <button className="ghost" disabled={step === 0} onClick={() => setStep((value) => Math.max(0, value - 1))}>
            <span aria-hidden="true">←</span> назад
          </button>
          <div className="modal-dots">
            {sections.map((section, index) => <i key={section.heading} className={index === step ? 'on' : ''} />)}
          </div>
          <button className="primary" onClick={() => (last ? onClose() : setStep((value) => value + 1))}>
            {last ? 'Понятно' : 'далее'} <span aria-hidden="true">→</span>
          </button>
        </div>
      </>}
    </div>
  </div>;
}
