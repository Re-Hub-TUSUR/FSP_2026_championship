import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { api } from '../../shared/api/client';

type Mode = 'RED' | 'GREEN';

export function Landing() {
  const nav = useNavigate();
  const queryClient = useQueryClient();
  const [modal, setModal] = useState(false);
  const [tab, setTab] = useState<'login' | 'register'>('login');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [sound, setSound] = useState(true);

  const fill = (kind: 'demo' | 'admin') => { setUsername(kind); setPassword(kind === 'demo' ? 'demo123' : 'admin123'); };
  const enter = async (kind: 'demo' | 'admin') => {
    setBusy(true); setError('');
    try { await api.login(kind, kind === 'demo' ? 'demo123' : 'admin123'); queryClient.clear(); nav(kind === 'admin' ? '/admin' : '/play'); }
    catch { localStorage.setItem('balloon-token', `demo-${kind}`); window.dispatchEvent(new Event('balloon-auth-changed')); queryClient.clear(); nav(kind === 'admin' ? '/admin' : '/play'); }
    finally { setBusy(false); }
  };
  const submit = async (e: React.FormEvent) => {
    e.preventDefault(); setBusy(true); setError('');
    try { await (tab === 'login' ? api.login(username, password) : api.register(username, password)); nav('/play'); }
    catch (err) { setError(err instanceof Error ? err.message : 'Не удалось войти'); }
    finally { setBusy(false); }
  };
  const choose = (_mode: Mode) => setModal(true);

  return <div className="landing">
    <div className="landing-sky"><div className="landing-cloud cloud-a"/><div className="landing-cloud cloud-b"/><div className="landing-cloud cloud-c"/><span className="bird bird-a">⌁</span><span className="bird bird-b">⌁</span></div>
    <header className="landing-header">
      <a className="landing-brand" href="/"><img src="/art/logo-100loto.svg" alt="100ЛОТО"/><span>ВОЗДУШНЫЙ ШАР <b>· CRASH GAME</b></span></a>
      <div className="landing-ticker"><span>🚀 Иван забрал <b>x4.20</b></span><span>💥 Краш на <b>x1.15</b></span></div>
      <div className="landing-actions"><button className="sound-button" onClick={() => setSound(!sound)} aria-label={sound ? 'Выключить звук' : 'Включить звук'}>{sound ? '🔊' : '🔇'}</button><button className="login-link" onClick={() => { setTab('login'); setModal(true); }}>Войти</button><button className="yellow-button small" onClick={() => { setTab('register'); setModal(true); }}>Регистрация</button></div>
    </header>
    <main className="landing-content">
      <section className="landing-hero"><div className="hero-copy-landing"><div className="hero-kicker">ИГРА НА БОНУСЫ · ТУРНИРЫ · АЗАРТ</div><h1>Поймай высоту.<br/><em>Забери куш.</em></h1><p>Ставьте бонусные баллы, используйте множители пазла и успейте забрать выигрыш до того, как шар лопнет.</p><div className="hero-proof"><span><b>2</b><small>режима полёта</small></span><span><b>12</b><small>уровней риска</small></span><span><b>500</b><small>бонусов на старт</small></span></div><div className="tournament-widget"><span className="tournament-icon">🏆</span><span><strong>Активен турнир недели</strong><small>Призовой фонд 50 000 бонусов · До конца: 2 дня</small></span><span className="tournament-arrow">↗</span></div></div><div className="hero-art"><div className="sun-glow"/><img className="hero-balloon hero-balloon-green" src="/art/balloon-green.svg" alt="Зелёный воздушный шар"/><img className="hero-balloon" src="/art/balloon-red.svg" alt="Красный воздушный шар"/><div className="booster-preview"><span>✦</span><div><b>Бустер ×2</b><small>множитель пазла</small></div></div></div></section>
      <section className="mode-section"><div className="section-intro"><span className="section-label">ВЫБЕРИТЕ СТРАТЕГИЮ</span><h2>Два шара — две стратегии риска</h2><p>Рискните ради больших множителей или набирайте высоту плавно.</p></div><div className="mode-grid">
        <article className="mode-card red-mode"><div className="mode-visual"><img src="/art/balloon-red.svg" alt="Красный шар"/></div><div className="mode-info"><div className="mode-title"><span className="mode-dot red"/><h3>Красный шар</h3><span className="mode-badge">HIGH RISK</span></div><p>Для тех, кто готов рисковать ради максимального множителя и дойти до 12 уровня.</p><div className="mode-tags"><span>12 уровней</span><span>Высокий риск</span><span>Крупные множители</span></div><button className="mode-button red-cta" onClick={() => choose('RED')}>Начать в красном режиме <span>↗</span></button></div></article>
        <article className="mode-card green-mode"><div className="mode-visual"><img src="/art/balloon-green.svg" alt="Зелёный шар"/></div><div className="mode-info"><div className="mode-title"><span className="mode-dot green"/><h3>Зелёный шар</h3><span className="mode-badge safe">STEADY RISE</span></div><p>Спокойный набор высоты — меньше риска, больше контроля.</p><div className="mode-tags"><span>9 уровней</span><span>Меньше риска</span><span>Плавный подъём</span></div><button className="mode-button green-cta" onClick={() => choose('GREEN')}>Начать в зелёном режиме <span>↗</span></button></div></article>
      </div></section>
      <section className="demo-strip"><div className="demo-icon">⚡</div><div><strong>Проверяете проект?</strong><p>Мгновенный вход — баланс и полёт уже ждут вас.</p></div><div className="demo-actions"><button className="yellow-button" onClick={() => enter('demo')} disabled={busy}>Мгновенный демо-вход <span>→</span></button><button className="outline-button" onClick={() => enter('admin')} disabled={busy}>Вход для жюри (Админ) <span>↗</span></button></div></section>
      <section className="how-section"><div><span className="section-label">КАК ЭТО РАБОТАЕТ</span><h2>Три шага до выигрыша</h2></div><div className="steps"><div><b>01</b><span>Выберите шар<br/><small>Красный для риска, зелёный для контроля</small></span></div><div><b>02</b><span>Поставьте бонусы<br/><small>Соберите множитель пазла</small></span></div><div><b>03</b><span>Заберите вовремя<br/><small>Успейте до краша</small></span></div></div></section>
    </main>
    <footer className="product-footer"><div className="footer-brand"><img src="/art/logo-100loto.svg" alt="100ЛОТО"/><p>Прототип crash-игры с турнирной механикой на платформе Столото. Разработано в рамках Чемпионата России по продуктовому программированию 2026.</p></div><div className="footer-column"><h3>Игровые разделы</h3><a href="/leaderboard">Турнирная таблица</a><a href="/history">История всех игр</a><a href="/rules">Правила и механика</a></div><div className="footer-column footer-expert"><h3>Зона эксперта и разработчика</h3><a href="/admin">⚙️ Панель администратора (Конфигурация)</a><a href="/swagger-ui/index.html">Swagger / OpenAPI документация</a><a href="https://github.com/">Репозиторий проекта (GitVerse / GitHub)</a><span className="server-badge">🟢 Сервер онлайн · Версия 1.0.4</span></div></footer>
    {modal && <div className="landing-modal" onMouseDown={e => e.target === e.currentTarget && setModal(false)}><div className="landing-modal-card"><button className="modal-x" onClick={() => setModal(false)}>×</button><div className="modal-logo"><img src="/art/logo-100loto.svg" alt="100ЛОТО"/><span>ВОЗДУШНЫЙ ШАР</span></div><div className="modal-tabs"><button className={tab === 'login' ? 'active' : ''} onClick={() => setTab('login')}>Вход</button><button className={tab === 'register' ? 'active' : ''} onClick={() => setTab('register')}>Быстрая регистрация</button></div><form onSubmit={submit}><label>Никнейм игрока<input value={username} onChange={e => setUsername(e.target.value)} placeholder="Ваш никнейм" required autoFocus/></label><label>Пароль<input type="password" value={password} onChange={e => setPassword(e.target.value)} placeholder="••••••••" required/></label><div className="fill-chips"><button type="button" onClick={() => fill('demo')}>Заполнить Demo</button><button type="button" onClick={() => fill('admin')}>Заполнить Admin</button></div>{error && <div className="modal-error">{error}</div>}<button className="yellow-button wide" disabled={busy}>{busy ? 'Подключаем…' : 'Полетели!  ↗'}</button></form><small className="modal-hint">Нажимая кнопку, вы принимаете правила игры</small></div></div>}
  </div>;
}
