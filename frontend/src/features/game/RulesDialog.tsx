import { useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../../shared/api/client';

/** Правила игры, доступные прямо с экрана ставки — до подтверждения выбора. */
export function RulesDialog({ onClose }: { onClose: () => void }) {
  const { data, isLoading } = useQuery({ queryKey: ['rules'], queryFn: api.rules });

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  return <div className="modal-backdrop" onClick={onClose}>
    <div className="modal-card" role="dialog" aria-modal="true" aria-label="Правила игры" onClick={(event) => event.stopPropagation()}>
      <div className="modal-head">
        <div><span className="eyebrow">ПЕРЕД СТАВКОЙ</span><h2>{data?.title || 'Правила игры'}</h2></div>
        <button className="modal-close" onClick={onClose} aria-label="Закрыть">×</button>
      </div>
      {isLoading ? <div className="loading"><div className="spinner" />Загрузка правил…</div> : <div className="modal-body">
        {(data?.sections || []).map((section) => <article key={section.heading}>
          <h3>{section.heading}</h3>
          <p>{section.text}</p>
        </article>)}
      </div>}
      <button className="primary cta" onClick={onClose}>Понятно</button>
    </div>
  </div>;
}
