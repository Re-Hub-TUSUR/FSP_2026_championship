import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../shared/api/client';

const steps = [
  { n: '01', title: 'Выберите шар', text: 'Красный — для риска, зелёный — для контроля' },
  { n: '02', title: 'Поставьте бонусы', text: 'Номинал задаёт и уровень бустера' },
  { n: '03', title: 'Заберите вовремя', text: 'Успейте зафиксировать выигрыш до взрыва' }
];

const PASSWORD_MIN_LENGTH = 6;
const PASSWORD_MAX_LENGTH = 128;
const PASSWORD_REQUIREMENTS = `Пароль должен содержать от ${PASSWORD_MIN_LENGTH} до ${PASSWORD_MAX_LENGTH} символов.`;

/**
 * Spring may return either the service's English message or a technical
 * MethodArgumentNotValidException description.  Neither is useful to a
 * player, so keep all password-length variants behind one stable UI message.
 */
const registrationErrorMessage = (error: unknown): string => {
  const message = error instanceof Error ? error.message : '';
  const normalized = message.toLocaleLowerCase();
  const mentionsPassword = /password|парол/.test(normalized);
  const mentionsLength = /length|size|character|символ|длин|6\s*[-–—]\s*128/.test(normalized);
  const mentionsValidation = /methodargumentnotvalid|validation failed|field error|registerrequest/.test(normalized);
  if (mentionsPassword && (mentionsLength || mentionsValidation)) return PASSWORD_REQUIREMENTS;
  return message || 'Не удалось зарегистрироваться';
};

export function Landing() {
  const nav = useNavigate();
  const queryClient = useQueryClient();
  const [modal, setModal] = useState(false);
  const [tab, setTab] = useState<'login' | 'register'>('login');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const { data: red } = useQuery({ queryKey: ['config', 'RED'], queryFn: () => api.config('RED') });
  const { data: green } = useQuery({ queryKey: ['config', 'GREEN'], queryFn: () => api.config('GREEN') });
  const top = (value?: number) => (value ? `×${value.toFixed(2)}` : '—');

  const fill = (kind: 'demo' | 'admin') => { setUsername(kind); setPassword(kind === 'demo' ? 'demo123' : 'admin123'); };

  const enter = async (kind: 'demo' | 'admin') => {
    setBusy(true); setError('');
    try {
      await api.login(kind, kind === 'demo' ? 'demo123' : 'admin123');
      queryClient.clear();
      nav(kind === 'admin' ? '/admin' : '/play');
    } catch (err) {
      // Неудачный вход никогда не создаёт локальный токен и не выдаёт доступ.
      setError(err instanceof Error ? err.message : 'Не удалось войти');
      setModal(true);
    } finally { setBusy(false); }
  };

  const submit = async (event: React.FormEvent) => {
    event.preventDefault(); setError('');
    if (tab === 'register') {
      // Registration uses manual validation so the message is rendered in the
      // form instead of being hidden in the browser's native validation bubble.
      if (!username.trim()) { setError('Введите никнейм игрока.'); return; }
      if (!password) { setError('Введите пароль.'); return; }
      if (password.length < PASSWORD_MIN_LENGTH || password.length > PASSWORD_MAX_LENGTH) {
        setError(PASSWORD_REQUIREMENTS);
        return;
      }
    }
    setBusy(true);
    try {
      await (tab === 'login' ? api.login(username, password) : api.register(username, password));
      nav('/play');
    } catch (err) {
      setError(tab === 'register' ? registrationErrorMessage(err) : err instanceof Error ? err.message : 'Не удалось войти');
    } finally { setBusy(false); }
  };

  const open = (mode: 'login' | 'register') => { setTab(mode); setError(''); setModal(true); };

  return <div className="landing">
    <div className="landing-sky" aria-hidden="true">
      <img className="landing-cloud cloud-a" src="/assets/cloud-a.png" alt="" />
      <img className="landing-cloud cloud-b" src="/assets/cloud-b.png" alt="" />
      <img className="landing-cloud cloud-c" src="/assets/cloud-c.png" alt="" />
    </div>
    <header className="topbar">
      <div className="topbar-inner">
        <a className="brand" href="/">
          <img src="/assets/logo-balloon.png" alt="Воздушный шар" />
          <small>ВОЗДУШНЫЙ ШАР</small>
        </a>
        <div className="main-nav" />
        <div className="header-account">
          <button className="logout-button" onClick={() => open('login')}>Войти</button>
          <button className="primary" onClick={() => open('register')}>Играть бесплатно</button>
        </div>
      </div>
    </header>

    <div className="page">
      <section className="hero-card">
        <div className="hero-copy">
          <span className="eyebrow">БОНУСНАЯ ИГРА ПЛАТФОРМЫ 100ЛОТО</span>
          <h1>Поймай высоту.<br /><em>Забери куш.</em></h1>
          <p>Ставьте бонусные баллы, ловите бустеры пазла и успейте забрать выигрыш до того, как шар лопнет.</p>
          <div className="hero-stats">
            <span><b>2</b><small>режима полёта</small></span>
            <span><b>12</b><small>уровней риска</small></span>
            <span><b>500</b><small>бонусов на старт</small></span>
          </div>
          <div className="start-row">
            <button className="primary" onClick={() => open('register')}>Начать игру <span aria-hidden="true">→</span></button>
            <button className="ghost" onClick={() => enter('demo')} disabled={busy}>Демо-вход для жюри</button>
          </div>
        </div>
        <div className="hero-art">
          <img className="landing-balloon-green" src="/assets/balloon-green.png" alt="Зелёный воздушный шар" />
          <img className="landing-balloon-red" src="/assets/balloon-red.png" alt="Красный воздушный шар" />
        </div>
      </section>

      <section className="setup-card">
        <div className="section-title">
          <div><span className="eyebrow">ВЫБЕРИТЕ СТРАТЕГИЮ</span><h2>Два шара — две стратегии риска</h2></div>
          <span className="muted">Возврат игроку 96% в обоих режимах</span>
        </div>
        <div className="theme-grid">
          <button className="theme-option" onClick={() => open('register')}>
            <img src="/assets/balloon-red.png" alt="Красный шар" />
            <span>
              <b>Красный шар</b>
              <small className="muted">{red?.levels ?? 12} уровней · высокий риск · до {top(red?.maxMultiplier)}</small>
            </span>
          </button>
          <button className="theme-option" onClick={() => open('register')}>
            <img src="/assets/balloon-green.png" alt="Зелёный шар" />
            <span>
              <b>Зелёный шар</b>
              <small className="muted">{green?.levels ?? 9} уровней · плавный подъём · до {top(green?.maxMultiplier)}</small>
            </span>
          </button>
        </div>
      </section>

      <div className="landing-white-section">
      <section className="draw-banner">
        <div>
          <span className="eyebrow">ПРОВЕРЯЕТЕ ПРОЕКТ?</span>
          <h2>Мгновенный вход — баланс и полёт уже ждут</h2>
        </div>
        <div className="draw-timer" style={{ gap: 10 }}>
          <button className="primary" onClick={() => enter('demo')} disabled={busy}>Демо-вход</button>
          <button className="ghost" onClick={() => enter('admin')} disabled={busy}>Вход для жюри (админ)</button>
        </div>
      </section>

      <section className="rules-layout">
        <div className="card">
          <span className="eyebrow">КАК ЭТО РАБОТАЕТ</span>
          <h2>Три шага до выигрыша</h2>
          <div className="rules-steps">
            {steps.map((step) => <div key={step.n}>
              <b>{step.n}</b>
              <span><strong>{step.title}</strong><br /><small className="muted">{step.text}</small></span>
            </div>)}
          </div>
        </div>
        <div className="card rules-card">
          <span className="eyebrow">ЧЕСТНОСТЬ РАУНДА</span>
          <h2>Точка взрыва известна до старта</h2>
          <article><h3>Хеш публикуется заранее</h3><p className="muted">SHA-256 фиксирует результат до начала раунда — изменить его задним числом нельзя.</p></article>
          <article><h3>Проверка в истории</h3><p className="muted">После раунда сервер раскрывает зерно, и любой результат можно пересчитать.</p></article>
          <article><h3>Только бонусные баллы</h3><p className="muted">В игре нет реальных денег: ставка и выигрыш — бонусы платформы.</p></article>
        </div>
      </section>
      </div>
    </div>

    <footer className="product-footer">
      <div className="footer-brand">
        <img src="/assets/logo-100loto.svg" alt="100ЛОТО" />
        <p className="muted">Прототип crash-игры с турнирной механикой на платформе Столото. Разработано для Чемпионата России по спортивному программированию 2026.</p>
      </div>
      <div className="footer-column">
        <h3>Игровые разделы</h3>
        <a href="/leaderboard">Турнирная таблица</a>
        <a href="/history">История всех игр</a>
        <a href="/rules">Правила и механика</a>
      </div>
      <div className="footer-column">
        <h3>Зона эксперта и разработчика</h3>
        <a href="/admin">Панель администратора</a>
        <a href="/swagger-ui/index.html">Swagger / OpenAPI</a>
        <span className="live-pill"><i /> Сервер онлайн · Версия 1.1.0</span>
      </div>
    </footer>

    {modal && <div className="modal-backdrop" onMouseDown={(event) => event.target === event.currentTarget && setModal(false)}>
      <div className="modal-card upsell-card" role="dialog" aria-modal="true" aria-label="Вход в игру">
        <button className="modal-close" onClick={() => setModal(false)} aria-label="Закрыть">×</button>
        <img src="/assets/logo-100loto.svg" alt="100ЛОТО" height={28} style={{ margin: '0 auto 8px' }} />
        <span className="eyebrow">ВОЗДУШНЫЙ ШАР</span>

        <div className="tabs" style={{ justifyContent: 'center', margin: '14px 0' }}>
          <button className={tab === 'login' ? 'active' : ''} onClick={() => setTab('login')}>Вход</button>
          <button className={tab === 'register' ? 'active' : ''} onClick={() => setTab('register')}>Быстрая регистрация</button>
        </div>

        <form onSubmit={submit} noValidate={tab === 'register'} style={{ textAlign: 'left' }}>
          <label className="field-label" htmlFor="landing-username">Никнейм игрока</label>
          <input id="landing-username" value={username} onChange={(event) => setUsername(event.target.value)} placeholder="Ваш никнейм" required autoFocus />
          <label className="field-label" htmlFor="landing-password" style={{ marginTop: 12 }}>Пароль</label>
          <input id="landing-password" type="password" value={password} onChange={(event) => setPassword(event.target.value)} placeholder="••••••••" required minLength={tab === 'register' ? PASSWORD_MIN_LENGTH : undefined} maxLength={tab === 'register' ? PASSWORD_MAX_LENGTH : undefined} aria-describedby={tab === 'register' ? 'landing-password-hint' : undefined} autoComplete={tab === 'register' ? 'new-password' : 'current-password'} />
          {tab === 'register' && <small id="landing-password-hint" className="muted" style={{ display: 'block', marginTop: 6, fontSize: 11 }}>{PASSWORD_REQUIREMENTS}</small>}

          <div className="start-row" style={{ marginTop: 14, paddingTop: 0, border: 0 }}>
            <button type="button" className="text-button" onClick={() => fill('demo')}>Заполнить demo</button>
            <button type="button" className="text-button" onClick={() => fill('admin')}>Заполнить admin</button>
          </div>

          {error && <div className="form-error" role="alert">{error}</div>}

          <button className={`primary ${busy ? 'is-loading' : ''}`} disabled={busy} style={{ width: '100%', marginTop: 14 }}>
            {busy ? 'Подключаем…' : 'Полетели!'} <span aria-hidden="true">→</span>
          </button>
        </form>

        <small className="muted" style={{ display: 'block', marginTop: 12 }}>Нажимая кнопку, вы принимаете правила игры</small>
      </div>
    </div>}
  </div>;
}
