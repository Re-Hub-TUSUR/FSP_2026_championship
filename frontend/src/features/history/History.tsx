import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { api, type Round } from '../../shared/api/client';

type Tab = 'mine' | 'all';
type ThemeFilter = 'ALL' | 'RED' | 'GREEN';
type Verdict = 'pending' | 'match' | 'mismatch' | 'unsupported';

const formatTime = (value?: string) =>
  new Date(value || Date.now()).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
const number = (value: number) => Math.round(value).toLocaleString('ru-RU').replace(/ /g, ' ');
const profitOf = (round: Round) => round.profit ?? (round.payout || 0) - round.betAmount;
const isWin = (round: Round) => (round.payout || 0) > 0;
/** Выигрыш — коэффициент фиксации, проигрыш — коэффициент уровня, на котором шар лопнул. */
const shownMultiplier = (round: Round) =>
  isWin(round) ? round.cashedOutMultiplier || round.multiplier : round.crashMultiplier ?? round.multiplier;

async function sha256Hex(text: string) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, '0')).join('');
}

/** Проверка честности: сервер раскрывает зерно, а браузер сам пересчитывает хеш, опубликованный до старта. */
function FairnessDialog({ round, onClose }: { round: Round; onClose: () => void }) {
  const { data: proof, isLoading, isError } = useQuery({ queryKey: ['proof', round.roundId], queryFn: () => api.proof(round.roundId) });
  const [verdict, setVerdict] = useState<Verdict>('pending');

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  useEffect(() => {
    if (!proof?.seed) return;
    if (!globalThis.crypto?.subtle) { setVerdict('unsupported'); return; }
    let cancelled = false;
    sha256Hex(`${proof.seed}:${proof.roundId}:${proof.configVersion}`)
      .then((hash) => { if (!cancelled) setVerdict(`sha256:${hash}` === proof.commitment ? 'match' : 'mismatch'); })
      .catch(() => { if (!cancelled) setVerdict('unsupported'); });
    return () => { cancelled = true; };
  }, [proof]);

  return <div className="modal-backdrop" onClick={onClose}>
    <div className="modal-card fairness-card" role="dialog" aria-modal="true" aria-labelledby="fairness-title" onClick={(event) => event.stopPropagation()}>
      <div className="modal-head">
        <div><span className="eyebrow">PROVABLY FAIR · SHA-256</span><h2 id="fairness-title">Честность раунда</h2></div>
        <button className="modal-close" onClick={onClose} aria-label="Закрыть">×</button>
      </div>

      {isLoading ? <div className="empty"><div className="spinner" /></div>
        : isError || !proof?.seed ? <div className="state-error">
          <span>!</span>
          <b>Не удалось получить доказательство</b>
          <p className="muted">Проверка доступна владельцу раунда, пока сервер хранит раунд в истории.</p>
        </div>
        : <>
          <div className={`fairness-verdict ${verdict}`}>
            {verdict === 'match' ? '✓ Хеш совпадает — результат был зафиксирован до старта'
              : verdict === 'mismatch' ? '✗ Хеш не совпадает с опубликованным до старта'
              : verdict === 'unsupported' ? 'Браузер не поддерживает SHA-256 — сверьте значения вручную'
              : 'Пересчитываем хеш…'}
          </div>
          <dl className="fairness-list">
            <dt>Хеш до старта</dt><dd><code>{proof.commitment}</code></dd>
            <dt>Раскрытое зерно</dt><dd><code>{proof.seed}</code></dd>
            <dt>Версия настроек</dt><dd>{proof.configVersion}</dd>
            <dt>Точка взрыва</dt><dd><b className="crash">уровень {proof.popLevel} · ×{(proof.crashMultiplier ?? shownMultiplier(round)).toFixed(2)}</b></dd>
          </dl>
          <p className="muted">
            Браузер пересчитал sha256(зерно:раунд:версия) и сравнил с хешем, который сервер показал до начала полёта.
            Первый подъём проходит без риска, дальше шар лопается на первом уровне, где HMAC-SHA256(зерно, раунд:уровень) меньше шанса взрыва этого уровня.
          </p>
        </>}
    </div>
  </div>;
}

export function History() {
  const [tab, setTab] = useState<Tab>('mine');
  const [theme, setTheme] = useState<ThemeFilter>('ALL');
  const [fairRound, setFairRound] = useState<Round>();
  const mineQuery = useQuery({ queryKey: ['history', 'mine'], queryFn: api.myHistory, refetchInterval: 5000, refetchIntervalInBackground: true });
  const allQuery = useQuery({ queryKey: ['history'], queryFn: api.history, refetchInterval: 5000, refetchIntervalInBackground: true, enabled: tab === 'all' });

  const mine = mineQuery.data ?? [];
  const mineIds = useMemo(() => new Set(mine.map((round) => round.roundId)), [mine]);
  const source = tab === 'mine' ? mineQuery : allQuery;
  const rows = (source.data ?? []).filter((round) => theme === 'ALL' || round.theme === theme);

  const wins = mine.filter(isWin);
  const total = mine.reduce((sum, round) => sum + profitOf(round), 0);
  const best = wins.reduce((max, round) => Math.max(max, shownMultiplier(round)), 0);
  const red = mine.filter((round) => round.theme === 'RED').length;

  return <div className="play-layout">
    <div className="section-title">
      <div>
        <span className="eyebrow">ПОЛЁТЫ И РЕЗУЛЬТАТЫ</span>
        <h1>История раундов</h1>
        <p className="muted">Разбирайте каждый полёт и проверяйте честность результата.</p>
      </div>
      <span className="live-pill"><i /> LIVE-ЛОГ</span>
    </div>

    <div className="leader-toolbar">
      <div className="tabs" role="tablist">
        <button role="tab" aria-selected={tab === 'mine'} className={tab === 'mine' ? 'active' : ''} onClick={() => setTab('mine')}>Мои раунды</button>
        <button role="tab" aria-selected={tab === 'all'} className={tab === 'all' ? 'active' : ''} onClick={() => setTab('all')}>Все игроки <small className="muted">общий пульс</small></button>
      </div>
      <label className="history-filter">
        <span className="eyebrow">ТЕМА</span>
        <select value={theme} onChange={(event) => setTheme(event.target.value as ThemeFilter)} aria-label="Фильтр по теме">
          <option value="ALL">Все темы</option>
          <option value="RED">Красный шар</option>
          <option value="GREEN">Зелёный шар</option>
        </select>
      </label>
    </div>

    <div className="history-summary">
      <div><small className="eyebrow">ВСЕГО РАУНДОВ</small><b>{mine.length}</b><span className="muted">красный {red} · зелёный {mine.length - red}</span></div>
      <div><small className="eyebrow">ПРОЦЕНТ ПОБЕД</small><b>{mine.length ? `${((wins.length / mine.length) * 100).toFixed(1)}%` : '0%'}</b><span className="muted">забрал выигрыш · {wins.length} из {mine.length}</span></div>
      <div><small className="eyebrow">РЕКОРД МНОЖИТЕЛЯ</small><b>{best ? `×${best.toFixed(2)}` : '—'}</b><span className="muted">лучшая фиксация</span></div>
      <div><small className="eyebrow">ЧИСТЫЙ ДОХОД</small><b className={total >= 0 ? 'win' : 'crash'}>{total >= 0 ? '+' : ''}{number(total)} ★</b><span className="muted">по вашим раундам</span></div>
    </div>

    {source.isLoading ? <div className="card"><div className="spinner" /></div>
      : source.isError ? <div className="state-error">
        <span>!</span>
        <b>Не получилось загрузить историю</b>
        <p className="muted">Проверьте соединение и попробуйте ещё раз.</p>
        <button className="ghost" onClick={() => source.refetch()}>Повторить</button>
      </div>
      : <div className="table history-table">
        <div className="table-head">
          <span>Время</span><span>Шар</span><span>Ставка · бустер</span><span>Высота</span><span>Множитель</span>
          <span>Выплата</span><span>Очки</span><span>Награда</span><span>Честность</span>
        </div>
        {rows.map((round) => {
          const profit = profitOf(round);
          const own = mineIds.has(round.roundId);
          return <div className="row" key={round.roundId}>
            <time>{formatTime(round.createdAt)}</time>
            <span className="history-theme">
              <img src={round.theme === 'GREEN' ? '/assets/balloon-green.png' : '/assets/balloon-red.png'} alt="" width={18} height={24} />
              {round.theme === 'RED' ? 'Красный' : 'Зелёный'}
            </span>
            <span><b>{number(round.betAmount)} ★</b>{round.boosterMultiplier > 1 && <small className="history-booster">бустер ×{round.boosterMultiplier}</small>}</span>
            <span>ур. {round.level} <small className="muted">из {round.levels}</small></span>
            <span className={`badge ${isWin(round) ? 'save' : 'lost'}`}>×{shownMultiplier(round).toFixed(2)} · {isWin(round) ? 'забрал' : 'краш'}</span>
            <b className={profit >= 0 ? 'win' : 'crash'}>{profit >= 0 ? '+' : ''}{number(profit)} ★</b>
            <span className="history-points">+{round.points || 0}</span>
            <span>{round.reward
              ? <span className="history-reward"><img src="/assets/puzzle.png" alt="" width={18} height={18} />Фрагмент #{round.reward.fragment + 1}</span>
              : '—'}</span>
            <span>
              <button
                className="ghost fairness-button"
                disabled={!own}
                title={own ? 'Проверить честность раунда' : 'Проверка доступна владельцу раунда'}
                onClick={() => setFairRound(round)}
              >Проверить</button>
            </span>
          </div>;
        })}
        {!rows.length && <div className="empty">
          <span>🎈</span>
          <b>{tab === 'mine' ? 'История пока пуста' : 'Завершённых полётов пока нет'}</b>
          <p className="muted">Сыграйте первый раунд, и он появится здесь.</p>
          <Link className="primary" to="/play">Сделать первый полёт <span aria-hidden="true">→</span></Link>
        </div>}
      </div>}

    {fairRound && <FairnessDialog round={fairRound} onClose={() => setFairRound(undefined)} />}
  </div>;
}
