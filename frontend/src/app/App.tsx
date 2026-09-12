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
import { Landing } from '../features/landing/Landing';

function Header() {
  const queryClient = useQueryClient();
  const nav = useNavigate();
  const [token, setToken] = useState(() => localStorage.getItem('balloon-token'));
  useEffect(() => {
    const sync = () => setToken(localStorage.getItem('balloon-token'));
    window.addEventListener('storage', sync);
    window.addEventListener('balloon-auth-changed', sync);
    return () => { window.removeEventListener('storage', sync); window.removeEventListener('balloon-auth-changed', sync); };
  }, []);
  const { data } = useQuery({ queryKey: ['me'], queryFn: api.me, refetchInterval: 3000, enabled: Boolean(token) });
  const location = useLocation();
  if (location.pathname === '/') return null;
  const logout = async () => { try { await api.logout(); } catch { /* сервер может быть недоступен — локальная сессия всё равно закрывается */ } finally { queryClient.clear(); setToken(null); nav('/', { replace: true }); } };
  return (
    <header className="topbar">
      <div className="topbar-inner">
        <NavLink to={token ? '/play' : '/register'} className="brand">
          <img src="/art/logo-100loto.svg" alt="100ЛОТО" />
          <span><small>ВОЗДУШНЫЙ ШАР</small></span>
        </NavLink>
        {token ? (
          <>
            <nav className="main-nav">
              <NavLink to="/play" className={location.pathname.startsWith('/round') || location.pathname === '/play' ? 'active' : ''}><span>🎈</span> Игра</NavLink>
              <NavLink to="/leaderboard" className={location.pathname === '/leaderboard' ? 'active' : ''}><span>♛</span> Рейтинг</NavLink>
              <NavLink to="/history" className={location.pathname === '/history' ? 'active' : ''}><span>◷</span> История</NavLink>
              <NavLink to="/rules" className={location.pathname === '/rules' ? 'active' : ''}><span>?</span> Правила</NavLink>
              {data?.role === 'ADMIN' && <NavLink to="/admin" className={location.pathname === '/admin' ? 'active' : ''}><span>⚙</span> Админ</NavLink>}
            </nav>
            <div className="header-account">
              <NavLink to={data?.role === 'ADMIN' ? '/admin' : '/profile'} className="account-link" title="Профиль">
                <span className="account-avatar">{(data?.username || 'Д').slice(0, 1).toUpperCase()}</span>
                <span className="account-name">{data?.username || 'Загрузка…'}</span>
              </NavLink>
              <div className="balance-pill"><span className="coin">★</span><span><small>ВАШ БАЛАНС</small><b>{data?.balance ?? '—'}</b></span></div>
              <button type="button" className="logout-button" onClick={logout}>Выйти</button>
            </div>
          </>
        ) : (
          <div className="header-account auth-actions">
            <NavLink to="/login" className="account-link">Войти</NavLink>
            <NavLink to="/register" className="primary compact-button">Регистрация</NavLink>
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
  if (isLoading) return <div className="card loading"><div className="spinner" />Проверяем доступ…</div>;
  return data?.role === 'ADMIN' ? children : <Navigate to="/play" replace />;
}

export default function App() { const location = useLocation(); return <div className="app"><Header /><main className={location.pathname === '/' ? 'landing-page-wrap' : 'page'}><Routes><Route path="/" element={<Landing />} /><Route path="/login" element={<Navigate to="/" replace />} /><Route path="/register" element={<Navigate to="/" replace />} /><Route path="/play" element={<RequireAuth><Play /></RequireAuth>} /><Route path="/round/:id" element={<RequireAuth><RoundView /></RequireAuth>} /><Route path="/round/:id/result" element={<RequireAuth><Result /></RequireAuth>} /><Route path="/leaderboard" element={<RequireAuth><Leaderboard /></RequireAuth>} /><Route path="/history" element={<RequireAuth><History /></RequireAuth>} /><Route path="/rules" element={<RequireAuth><Rules /></RequireAuth>} /><Route path="/profile" element={<RequireAuth><Profile /></RequireAuth>} /><Route path="/admin" element={<RequireAdmin><Admin /></RequireAdmin>} /><Route path="*" element={<Navigate to="/" replace />} /></Routes></main>{location.pathname !== '/' && <footer>100ЛОТО · Игра с виртуальными очками <span>•</span> Версия 1.0</footer>}</div> }
