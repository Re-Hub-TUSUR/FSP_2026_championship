import { useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, type GameConfig, type Theme } from '../../shared/api/client';
import { buildLadder, hazardAt } from '../../shared/game/engine';

const engineOf = (config: GameConfig) => ({
  levels: config.levels,
  baseHazard: config.baseHazard ?? 0.12,
  hazardGrowth: config.hazardGrowth ?? 1.115,
  maxHazard: config.maxHazard ?? 0.6,
  rtp: config.rtp ?? 0.96
});

const formulaLadder = (config: GameConfig) => buildLadder(engineOf(config)).slice(1);
const percent = (value: number) => `${(value * 100).toFixed(1)}%`;

export function ConfigEditor() {
  const client = useQueryClient();
  const [theme, setTheme] = useState<Theme>('RED');
  const { data, isLoading, refetch } = useQuery({
    queryKey: ['admin-config', theme],
    queryFn: () => api.adminConfig(theme),
    refetchOnWindowFocus: false,
    staleTime: Infinity
  });
  const [draft, setDraft] = useState<GameConfig | null>(null);
  const [manualLadder, setManualLadder] = useState(false);
  const [error, setError] = useState('');
  const [saved, setSaved] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!data) return;
    setDraft(data);
    setManualLadder((data.ladderOverride?.length || 0) > 0);
    setError('');
  }, [data]);

  // Сообщения относятся к конкретной теме: при переключении их надо убрать.
  useEffect(() => { setError(''); setSaved(''); }, [theme]);

  if (isLoading || !draft) return <div className="card loading"><div className="spinner" />Загрузка настроек…</div>;

  const patch = (values: Partial<GameConfig>) => { setDraft({ ...draft, ...values }); setSaved(''); };

  const ladder = manualLadder && draft.ladderOverride?.length === draft.levels ? draft.ladderOverride : formulaLadder(draft);
  const weights = draft.boosterWeights?.length === draft.levels
    ? draft.boosterWeights
    : Array.from({ length: draft.levels }, () => 1 / draft.levels);
  const weightSum = weights.reduce((sum, weight) => sum + Math.max(0, weight), 0) || 1;

  const survival: number[] = [];
  let alive = 1;
  for (let level = 1; level <= draft.levels; level++) { alive *= 1 - hazardAt(engineOf(draft), level); survival.push(alive); }

  const changeLevels = (value: number) => {
    const levels = Math.max(3, Math.min(30, Math.round(value) || 3));
    const next: GameConfig = { ...draft, levels };
    next.boosterWeights = Array.from({ length: levels }, () => 1 / levels);
    next.ladderOverride = manualLadder ? formulaLadder(next) : [];
    setDraft(next);
    setSaved('');
  };

  const toggleManual = (enabled: boolean) => {
    setManualLadder(enabled);
    patch({ ladderOverride: enabled ? formulaLadder(draft) : [] });
  };

  const changeLadderValue = (index: number, value: number) => {
    const next = [...ladder];
    next[index] = value;
    patch({ ladderOverride: next });
  };

  const changeWeight = (index: number, value: number) => {
    const next = [...weights];
    next[index] = Math.max(0, value);
    patch({ boosterWeights: next });
  };

  const changeBet = (index: number, field: 'amount' | 'boosterMultiplier', value: number) => {
    const betOptions = draft.betOptions.map((option, i) => i === index ? { ...option, [field]: value } : option);
    patch({ betOptions });
  };

  const save = async () => {
    setBusy(true);
    setError('');
    setSaved('');
    try {
      await api.updateConfig({ ...draft, ladderOverride: manualLadder ? ladder : [] });
      await refetch();
      client.invalidateQueries({ queryKey: ['config'] });
      setSaved('Конфигурация опубликована — применится со следующего раунда');
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Не удалось сохранить конфигурацию');
    } finally {
      setBusy(false);
    }
  };

  const reset = async () => {
    setBusy(true);
    setError('');
    setSaved('');
    try {
      await api.resetConfig(theme);
      await refetch();
      client.invalidateQueries({ queryKey: ['config'] });
      setSaved('Тема возвращена к значениям по умолчанию');
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Не удалось сбросить конфигурацию');
    } finally {
      setBusy(false);
    }
  };

  return <div className="card admin-card config-editor">
    <div className="tabs">
      <button className={theme === 'RED' ? 'active' : ''} onClick={() => setTheme('RED')}>🔴 Красная тема</button>
      <button className={theme === 'GREEN' ? 'active' : ''} onClick={() => setTheme('GREEN')}>🟢 Зелёная тема</button>
      <span className="config-version">версия {draft.version ?? 1} · лестница: {manualLadder ? 'вручную' : 'по формуле'}</span>
    </div>

    <h3 className="config-group">Базовые параметры</h3>
    <div className="admin-fields">
      <label>Идентификатор игры<input value={draft.gameId} onChange={(e) => patch({ gameId: e.target.value })} /></label>
      <label>Название<input value={draft.gameName || ''} onChange={(e) => patch({ gameName: e.target.value })} /></label>
      <label>Тип игры
        <select value={draft.gameType || 'CRASH'} onChange={(e) => patch({ gameType: e.target.value })}>
          <option value="CRASH">CRASH</option>
          <option value="LADDER">LADDER</option>
          <option value="LOTTERY">LOTTERY</option>
        </select>
      </label>
      <label className="config-switch">Тема доступна игрокам
        <input type="checkbox" checked={draft.active !== false} onChange={(e) => patch({ active: e.target.checked })} />
      </label>
    </div>

    <h3 className="config-group">Математическая модель</h3>
    <div className="admin-fields">
      <label>Количество уровней <small>3…30</small>
        <input type="number" min={3} max={30} value={draft.levels} onChange={(e) => changeLevels(+e.target.value)} />
      </label>
      <label>Базовый шанс взрыва <small>0.001…0.5</small>
        <input type="number" step="0.005" min={0.001} max={0.5} value={draft.baseHazard ?? 0.12} onChange={(e) => patch({ baseHazard: +e.target.value })} />
      </label>
      <label>Темп роста шанса <small>1.0…2.0</small>
        <input type="number" step="0.005" min={1} max={2} value={draft.hazardGrowth ?? 1.115} onChange={(e) => patch({ hazardGrowth: +e.target.value })} />
      </label>
      <label>Потолок шанса взрыва <small>до 0.95</small>
        <input type="number" step="0.01" min={0.01} max={0.95} value={draft.maxHazard ?? 0.6} onChange={(e) => patch({ maxHazard: +e.target.value })} />
      </label>
      <label>Возврат игроку (RTP) <small>0.5…1.0</small>
        <input type="number" step="0.01" min={0.5} max={1} value={draft.rtp ?? 0.96} onChange={(e) => patch({ rtp: +e.target.value })} />
      </label>
      <label>Максимальный коэффициент<input readOnly value={`×${(ladder[draft.levels - 1] ?? 0).toFixed(2)}`} /></label>
    </div>

    <h3 className="config-group">Ставки и множители бустеров</h3>
    <div className="bet-editor">
      {draft.betOptions.map((option, index) => <div key={option.id} className="bet-editor-row">
        <span>Тир {index + 1}</span>
        <label>Ставка<input type="number" min={1} value={option.amount} onChange={(e) => changeBet(index, 'amount', +e.target.value)} /></label>
        <label>Множитель очков<input type="number" step="0.5" min={1} max={100} value={option.boosterMultiplier} onChange={(e) => changeBet(index, 'boosterMultiplier', +e.target.value)} /></label>
      </div>)}
    </div>

    <h3 className="config-group">Начисление очков</h3>
    <div className="admin-fields">
      <label>Очки за уровень<input type="number" min={0} value={draft.pointsPerLevel} onChange={(e) => patch({ pointsPerLevel: +e.target.value })} /></label>
      <label>Бонус за cashout<input type="number" min={0} value={draft.pointsCashoutBonus ?? 0} onChange={(e) => patch({ pointsCashoutBonus: +e.target.value })} /></label>
      <label>Бонус за бустер<input type="number" min={0} value={draft.pointsBoosterBonus ?? 0} onChange={(e) => patch({ pointsBoosterBonus: +e.target.value })} /></label>
    </div>

    <h3 className="config-group">
      Уровни: коэффициенты и положение бустера
      <label className="config-switch inline">
        <input type="checkbox" checked={manualLadder} onChange={(e) => toggleManual(e.target.checked)} />
        задать коэффициенты вручную
      </label>
    </h3>
    <div className="table level-editor">
      <div className="row table-head"><span>Уровень</span><span>Коэффициент</span><span>Шанс взрыва</span><span>Шанс бустера</span><span>Возврат</span></div>
      {ladder.map((multiplier, index) => {
        const expected = survival[index] * multiplier;
        return <div className="row" key={index}>
          <span>{index + 1}</span>
          <span>
            {manualLadder
              ? <input type="number" step="0.01" min={1.01} value={multiplier} onChange={(e) => changeLadderValue(index, +e.target.value)} />
              : <b>×{multiplier.toFixed(2)}</b>}
          </span>
          <span>{percent(hazardAt(engineOf(draft), index + 1))}</span>
          <span><input type="number" step="0.01" min={0} value={+(Math.max(0, weights[index]) / weightSum).toFixed(4)} onChange={(e) => changeWeight(index, +e.target.value)} /></span>
          <span className={expected > 1 ? 'crash' : 'win'}>{percent(expected)}</span>
        </div>;
      })}
    </div>
    <p className="muted config-hint">
      Возврат — ожидаемая выплата при выходе на этом уровне: шанс долететь × коэффициент. Значения выше 100% означают
      игру в минус для платформы.
    </p>

    {error && <div className="form-error">{error}</div>}
    {saved && <div className="form-success">{saved}</div>}
    <div className="config-actions">
      <button className="primary cta" disabled={busy} onClick={save}>{busy ? 'Сохраняем…' : 'Опубликовать конфигурацию'} <span>→</span></button>
      <button className="ghost-button" disabled={busy} onClick={reset}>Сбросить тему к значениям по умолчанию</button>
    </div>
  </div>;
}
