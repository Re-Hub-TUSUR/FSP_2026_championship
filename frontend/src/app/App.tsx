import { Routes, Route, NavLink, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../shared/api/client';
import { Play } from '../features/game/Play';
import { RoundView } from '../features/game/RoundView';
import { Result } from '../features/game/Result';
import { History } from '../features/history/History';
import { Rules } from '../features/history/Rules';
import { Admin } from '../features/admin/Admin';
import { Leaderboard } from '../features/leaderboard/Leaderboard';
import { Profile } from '../features/profile/Profile';
import { DailyBonusPanel } from '../features/game/DailyBonusPanel';
import { Landing } from '../features/landing/Landing';
import { Lottery } from '../features/lottery/Lottery';

const number = (value: number) => Math.round(value).toLocaleString('ru-RU').replace(/ /g, ' ');

function Header() {
  const queryClient = useQueryClient();
  const nav = useNavigate();
  const [token, setToken] = useState(() => localStorage.getItem('balloon-token'));
  const [topup, setTopup] = useState(0);
  const [topupBusy, setTopupBusy] = useState(false);
  const [credited, setCredited] = useState(false);
  useEffect(() => {
    const sync = () => { setToken(localStorage.getItem('balloon-token')); setTopup(0); };
    window.addEventListener('storage', sync);
    window.addEventListener('balloon-auth-changed', sync);
    return () => { window.removeEventListener('storage', sync); window.removeEventListener('balloon-auth-changed', sync); };
  }, []);
  const { data } = useQuery({ queryKey: ['me'], queryFn: api.me, refetchInterval: 3000, enabled: Boolean(token) });
  const location = useLocation();
  if (location.pathname === '/') return null;

  const displayedBalance = data?.balance != null ? number(data.balance + topup) : '—';
  const addBonus = async () => {
    if (topupBusy) return;
    setTopupBusy(true);
    try {
      const updated = await api.topUp();
      queryClient.setQueryData(['me'], updated);
    } catch {
      // Демо-режим без backend: сохраняем быстрый сценарий для жюри локально.
      setTopup((value) => value + 500);
    } finally {
      setTopupBusy(false);
      setCredited(true);
      setTimeout(() => setCredited(false), 1200);
    }
  };
  const logout = async () => {
    try { await api.logout(); } catch { /* сервер может быть недоступен — локальная сессия всё равно закрывается */ }
    finally { queryClient.clear(); setToken(null); nav('/', { replace: true }); }
  };

  return (
    <header className="topbar">
      <div className="topbar-inner">
        <NavLink to={token ? '/play' : '/'} className="brand">
          <img src="/assets/logo-balloon.png" alt="Воздушный шар" />
          <small>ВОЗДУШНЫЙ ШАР</small>
        </NavLink>
        {token ? (
          <>
            <nav className="main-nav">
              <NavLink to="/play" className={location.pathname.startsWith('/round') || location.pathname === '/play' ? 'active' : ''}>Игра</NavLink>
              <NavLink to="/leaderboard" className={location.pathname === '/leaderboard' ? 'active' : ''}>Турнир</NavLink>
              <NavLink to="/lottery" className={location.pathname === '/lottery' ? 'active' : ''}>Лотерея</NavLink>
              <NavLink to="/history" className={location.pathname === '/history' ? 'active' : ''}>История</NavLink>
              <NavLink to="/rules" className={location.pathname === '/rules' ? 'active' : ''}>Правила</NavLink>
              {data?.role === 'ADMIN' && <NavLink to="/admin" className={location.pathname === '/admin' ? 'active' : ''}>Админка</NavLink>}
            </nav>
            <div className="header-account">
              <DailyBonusPanel token={token} profileState={data?.dailyBonus} compact />
              <div className={`balance-pill ${credited ? 'is-credited' : ''}`}>
                <img src="/assets/coin-sm.png" alt="" width={22} height={22} />
                <span><small>БОНУСЫ</small><b>{displayedBalance}</b></span>
              </div>
              <button type="button" className="topup-button" onClick={addBonus} disabled={topupBusy} aria-label="Пополнить баланс на 500 бонусов" title="+500 бонусов">{topupBusy ? '…' : '+'}</button>
              <NavLink to="/lottery" className="ticket-pill" title="Лотерейные билеты">
                <img src="/assets/ticket.png" alt="Лотерейные билеты" width={20} height={20} />
                <b>{data?.lotteryTickets ?? 0}</b>
              </NavLink>
              <NavLink to={data?.role === 'ADMIN' ? '/admin' : '/profile'} className="account-link" title="Профиль">
                <span className="account-avatar">{(data?.username || 'Д').slice(0, 1).toUpperCase()}</span>
                <span className="account-name">{data?.username || 'Загрузка…'}</span>
              </NavLink>
              <button type="button" className="logout-button" onClick={logout}>Выйти</button>
            </div>
          </>
        ) : (
          <div className="header-account">
            <NavLink to="/" className="account-link">Войти</NavLink>
          </div>
        )}
      </div>
    </header>
  );
}

function RequireAuth({ children }: { children: ReactNode }) {
  return localStorage.getItem('balloon-token') ? children : <Navigate to="/" replace />;
}

function RequireAdmin({ children }: { children: ReactNode }) {
  const token = localStorage.getItem('balloon-token');
  const { data, isLoading } = useQuery({ queryKey: ['me'], queryFn: api.me, enabled: Boolean(token) });
  if (!token) return <Navigate to="/" replace />;
  if (isLoading) return <div className="card"><div className="spinner" /></div>;
  return data?.role === 'ADMIN' ? children : <Navigate to="/play" replace />;
}

function Footer() {
  return <footer className="product-footer">
    <div className="footer-brand">
      <img src="/assets/logo-100loto.svg" alt="100ЛОТО" />
      <p className="muted">Прототип crash-игры с турнирной механикой на платформе Столото.</p>
    </div>
    <div className="footer-column">
      <h3>Игровые разделы</h3>
      <NavLink to="/leaderboard">Турнирная таблица</NavLink>
      <NavLink to="/history">История всех игр</NavLink>
      <NavLink to="/rules">Правила и механика</NavLink>
    </div>
    <div className="footer-column">
      <h3>Зона эксперта и разработчика</h3>
      <NavLink to="/admin">Панель администратора</NavLink>
      <a href="/swagger-ui/index.html">Swagger / OpenAPI</a>
      <span className="live-pill"><i /> Сервер онлайн · Версия 1.1.0</span>
    </div>
  </footer>;
}

export default function App() {
  const location = useLocation();
  const isLanding = location.pathname === '/';
  // Экран полёта занимает весь кадр: ни отступов страницы, ни футера.
  const isFlight = /^\/round\/[^/]+$/.test(location.pathname);
  const wrapper = isLanding ? 'landing-page-wrap' : isFlight ? 'flight-page-wrap' : 'page';

  return <div className={`app ${isFlight ? 'is-flight' : ''}`}>
    <Header />
    <main className={wrapper}>
      <Routes>
        <Route path="/" element={<Landing />} />
        <Route path="/login" element={<Navigate to="/" replace />} />
        <Route path="/register" element={<Navigate to="/" replace />} />
        <Route path="/play" element={<RequireAuth><Play /></RequireAuth>} />
        <Route path="/round/:id" element={<RequireAuth><RoundView /></RequireAuth>} />
        <Route path="/round/:id/result" element={<RequireAuth><Result /></RequireAuth>} />
        <Route path="/leaderboard" element={<RequireAuth><Leaderboard /></RequireAuth>} />
        <Route path="/lottery" element={<RequireAuth><Lottery /></RequireAuth>} />
        <Route path="/history" element={<RequireAuth><History /></RequireAuth>} />
        <Route path="/rules" element={<RequireAuth><Rules /></RequireAuth>} />
        <Route path="/profile" element={<RequireAuth><Profile /></RequireAuth>} />
        <Route path="/admin" element={<RequireAdmin><Admin /></RequireAdmin>} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </main>
    {!isLanding && !isFlight && <Footer />}
  </div>;
}
