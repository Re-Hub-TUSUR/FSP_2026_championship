import { useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, type GameConfig, type Theme } from '../../shared/api/client';
import { buildLadder, hazardAt } from '../../shared/game/engine';

const engineOf = (config: GameConfig, useManualHazards = true) => ({
  levels: config.levels,
  baseHazard: config.baseHazard ?? 0.12,
  hazardGrowth: config.hazardGrowth ?? 1.115,
  maxHazard: config.maxHazard ?? 0.6,
  rtp: config.rtp ?? 0.96,
  hazardOverride: useManualHazards && config.hazardOverride?.length === config.levels ? config.hazardOverride : undefined
});

const formulaLadder = (config: GameConfig) => buildLadder(engineOf(config)).slice(1);
const formulaHazards = (config: GameConfig) =>
  Array.from({ length: config.levels }, (_, index) => hazardAt(engineOf(config, false), index + 1));
const percent = (value: number) => `${(value * 100).toFixed(1)}%`;
const percentInput = (value: number) => Number.isFinite(value) ? Number((value * 100).toFixed(2)) : '';

const validateManualHazards = (hazards: number[], levels: number) => {
  if (hazards.length !== levels) return `Нужно задать шанс взрыва для каждого из ${levels} уровней`;
  if (hazards[0] !== 0) return 'Первый уровень всегда безопасный: шанс взрыва должен быть 0%';
  const invalid = hazards.findIndex((value, index) => index > 0 && (!Number.isFinite(value) || value < 0 || value > 0.95));
  return invalid >= 0 ? `Шанс взрыва на уровне ${invalid + 1} должен быть от 0% до 95%` : '';
};

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
  const [manualHazard, setManualHazard] = useState(false);
  const [error, setError] = useState('');
  const [saved, setSaved] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!data) return;
    setDraft(data);
    setManualLadder((data.ladderOverride?.length || 0) > 0);
    setManualHazard(data.hazardOverride?.length === data.levels);
    setError('');
  }, [data]);

  // Сообщения относятся к конкретной теме: при переключении их надо убрать.
  useEffect(() => { setError(''); setSaved(''); }, [theme]);

  if (isLoading || !draft) return <div className="card"><div className="spinner" />Загрузка настроек…</div>;

  const patch = (values: Partial<GameConfig>) => { setDraft({ ...draft, ...values }); setError(''); setSaved(''); };

  const ladder = manualLadder && draft.ladderOverride?.length === draft.levels ? draft.ladderOverride : formulaLadder(draft);
  const hazards = manualHazard && draft.hazardOverride?.length === draft.levels ? draft.hazardOverride : formulaHazards(draft);
  const requiredLevels = theme === 'RED' ? 12 : 9;
  const weights = draft.boosterWeights?.length === draft.levels
    ? draft.boosterWeights
    : Array.from({ length: draft.levels }, () => 1 / draft.levels);
  const weightSum = weights.reduce((sum, weight) => sum + Math.max(0, weight), 0) || 1;

  const survival: number[] = [];
  let alive = 1;
  for (let level = 1; level <= draft.levels; level++) { alive *= 1 - hazardAt(engineOf(draft), level); survival.push(alive); }

  const toggleManual = (enabled: boolean) => {
    setManualLadder(enabled);
    patch({ ladderOverride: enabled ? formulaLadder(draft) : [] });
  };

  const toggleManualHazard = (enabled: boolean) => {
    setManualHazard(enabled);
    // Переход в ручной режим не меняет математическую модель до первой правки администратора.
    patch({ hazardOverride: enabled ? formulaHazards(draft) : [] });
  };

  const changeLadderValue = (index: number, value: number) => {
    const next = [...ladder];
    next[index] = value;
    patch({ ladderOverride: next });
  };

  const changeHazardPercent = (index: number, value: string) => {
    if (index === 0) return;
    const entered = Number(value);
    if (!Number.isFinite(entered)) return;
    const next = [...hazards];
    next[index] = Math.max(0, Math.min(95, entered)) / 100;
    patch({ hazardOverride: next });
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
    setError('');
    setSaved('');
    const hazardOverride = manualHazard ? hazards : [];
    const hazardError = manualHazard ? validateManualHazards(hazardOverride, draft.levels) : '';
    if (hazardError) {
      setError(hazardError);
      return;
    }

    setBusy(true);
    try {
      await api.updateConfig({ ...draft, hazardOverride, ladderOverride: manualLadder ? ladder : [] });
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

  return <div className="card">
    <div className="tabs">
      <button className={theme === 'RED' ? 'active' : ''} onClick={() => setTheme('RED')}>🔴 Красная тема</button>
      <button className={theme === 'GREEN' ? 'active' : ''} onClick={() => setTheme('GREEN')}>🟢 Зелёная тема</button>
      <span className="config-version">
        версия {draft.version ?? 1} · шансы: {manualHazard ? 'вручную' : 'по формуле'} · лестница: {manualLadder ? 'вручную' : 'по формуле'}
      </span>
    </div>

    <h3 className="admin-group">Базовые параметры</h3>
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

    <h3 className="admin-group">Математическая модель</h3>
    <div className="admin-fields">
      <label>Количество уровней <small>фиксировано для темы</small>
        <input type="number" min={requiredLevels} max={requiredLevels} value={draft.levels} readOnly aria-readonly="true" />
      </label>
      <label>Базовый шанс взрыва <small>0.001…0.5</small>
        <input disabled={manualHazard} type="number" step="0.005" min={0.001} max={0.5} value={draft.baseHazard ?? 0.12} onChange={(e) => patch({ baseHazard: +e.target.value })} />
      </label>
      <label>Темп роста шанса <small>1.0…2.0</small>
        <input disabled={manualHazard} type="number" step="0.005" min={1} max={2} value={draft.hazardGrowth ?? 1.115} onChange={(e) => patch({ hazardGrowth: +e.target.value })} />
      </label>
      <label>Потолок шанса взрыва <small>до 0.95</small>
        <input disabled={manualHazard} type="number" step="0.01" min={0.01} max={0.95} value={draft.maxHazard ?? 0.6} onChange={(e) => patch({ maxHazard: +e.target.value })} />
      </label>
      <label>Возврат игроку (RTP) <small>0.5…1.0</small>
        <input type="number" step="0.01" min={0.5} max={1} value={draft.rtp ?? 0.96} onChange={(e) => patch({ rtp: +e.target.value })} />
      </label>
      <label>Максимальный коэффициент<input readOnly value={`×${(ladder[draft.levels - 1] ?? 0).toFixed(2)}`} /></label>
    </div>

    <h3 className="admin-group">Ставки и множители бустеров</h3>
    <div className="bet-editor">
      {draft.betOptions.map((option, index) => <div key={option.id} className="bet-editor-row">
        <span>Тир {index + 1}</span>
        <label>Ставка<input type="number" min={1} value={option.amount} onChange={(e) => changeBet(index, 'amount', +e.target.value)} /></label>
        <label>Множитель очков<input type="number" step="0.5" min={1} max={100} value={option.boosterMultiplier} onChange={(e) => changeBet(index, 'boosterMultiplier', +e.target.value)} /></label>
      </div>)}
    </div>

    <h3 className="admin-group">Начисление очков</h3>
    <div className="admin-fields">
      <label>Очки за уровень<input type="number" min={0} value={draft.pointsPerLevel} onChange={(e) => patch({ pointsPerLevel: +e.target.value })} /></label>
      <label>Бонус за cashout<input type="number" min={0} value={draft.pointsCashoutBonus ?? 0} onChange={(e) => patch({ pointsCashoutBonus: +e.target.value })} /></label>
      <label>Бонус за бустер<input type="number" min={0} value={draft.pointsBoosterBonus ?? 0} onChange={(e) => patch({ pointsBoosterBonus: +e.target.value })} /></label>
    </div>

    <h3 className="admin-group">Апсейл «Закрепи успех»</h3>
    <div className="admin-fields">
      <label>Минимальный выигрыш для предложения <small>MIN_WIN_AMOUNT</small>
        <input type="number" min={0} value={draft.minWinAmount ?? 10} onChange={(e) => patch({ minWinAmount: +e.target.value })} />
      </label>
      <label>Автозакрытие окна, сек <small>POPUP_TIMEOUT, 3…60</small>
        <input type="number" min={3} max={60} value={draft.popupTimeout ?? 10} onChange={(e) => patch({ popupTimeout: +e.target.value })} />
      </label>
    </div>

    <h3 className="admin-group">
      Уровни: коэффициенты, шансы взрыва и положение бустера
      <label className="config-switch inline">
        <input type="checkbox" checked={manualLadder} onChange={(e) => toggleManual(e.target.checked)} />
        задать коэффициенты вручную
      </label>
      <label className="config-switch inline">
        <input type="checkbox" checked={manualHazard} onChange={(e) => toggleManualHazard(e.target.checked)} />
        задать шансы взрыва вручную
      </label>
    </h3>
    <div className="table admin-preview-table">
      <div className="table-head"><span>Уровень</span><span>Коэффициент</span><span>Шанс взрыва</span><span>Шанс бустера</span><span>Возврат</span></div>
      {ladder.map((multiplier, index) => {
        const hazard = hazardAt(engineOf(draft), index + 1);
        const expected = survival[index] * multiplier;
        return <div className="row" key={index}>
          <span>{index + 1}</span>
          <span>
            {manualLadder
              ? <input type="number" step="0.01" min={1.01} value={multiplier} onChange={(e) => changeLadderValue(index, +e.target.value)} />
              : <b>×{multiplier.toFixed(2)}</b>}
          </span>
          <span>
            {manualHazard
              ? index === 0
                ? <b>0.0%</b>
                : <span className="manual-hazard-input">
                  <input
                    aria-label={`Шанс взрыва на уровне ${index + 1}, в процентах`}
                    type="number"
                    inputMode="decimal"
                    step="0.1"
                    min={0}
                    max={95}
                    value={percentInput(hazards[index] ?? NaN)}
                    onChange={(e) => changeHazardPercent(index, e.target.value)}
                  />
                  <small>%</small>
                </span>
              : percent(hazard)}
          </span>
          <span><input type="number" step="0.01" min={0} value={+(Math.max(0, weights[index]) / weightSum).toFixed(4)} onChange={(e) => changeWeight(index, +e.target.value)} /></span>
          <span className={expected > 1 ? 'crash' : 'win'}>{percent(expected)}</span>
        </div>;
      })}
    </div>
    {manualHazard && <p className="muted config-hint">
      Первый уровень остаётся безопасным. Для остальных уровней укажите вероятность взрыва в процентах; параметры формулы сохраняются для возврата к автоматическому расчёту.
    </p>}
    <p className="muted config-hint">
      Возврат — ожидаемая выплата при выходе на этом уровне: шанс долететь × коэффициент. Значения выше 100% означают
      игру в минус для платформы.
    </p>

    {error && <div className="form-error">{error}</div>}
    {saved && <div className="form-success">{saved}</div>}
    <div className="config-actions">
      <button className="primary" disabled={busy} onClick={save}>{busy ? 'Сохраняем…' : 'Опубликовать конфигурацию'} <span>→</span></button>
      <button className="ghost" disabled={busy} onClick={reset}>Сбросить тему к значениям по умолчанию</button>
    </div>
  </div>;
}
