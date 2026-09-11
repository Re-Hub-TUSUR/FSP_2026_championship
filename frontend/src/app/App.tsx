import { Routes, Route, NavLink, Navigate, useLocation } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { api } from '../shared/api/client';
import { Play } from '../features/game/Play';
import { RoundView } from '../features/game/RoundView';
import { Result } from '../features/game/Result';
import { History } from '../features/history/History';
import { Rules } from '../features/history/Rules';
import { Admin } from '../features/admin/Admin';

function Header() {
  const { data } = useQuery({ queryKey: ['me'], queryFn: api.me, refetchInterval: 3000 });
  const location = useLocation();
  return <header className="topbar"><div className="topbar-inner"><NavLink to="/play" className="brand"><img src="/art/logo-100loto.svg" alt="100ЛОТО" /><span><small>ВОЗДУШНЫЙ ШАР</small></span></NavLink><nav className="main-nav"><NavLink to="/play" className={location.pathname.startsWith('/round') || location.pathname === '/play' ? 'active' : ''}><span>🎈</span> Игра</NavLink><NavLink to="/history"><span>◷</span> История</NavLink><NavLink to="/rules"><span>?</span> Правила</NavLink><NavLink to="/admin"><span>⚙</span> Кабинет</NavLink></nav><div className="balance-pill"><span className="coin">★</span><span><small>ВАШ БАЛАНС</small><b>{data?.balance ?? 1000}</b></span></div></div></header>
}
export default function App() { return <div className="app"><Header /><main className="page"><Routes><Route path="/" element={<Navigate to="/play" replace />} /><Route path="/play" element={<Play />} /><Route path="/round/:id" element={<RoundView />} /><Route path="/round/:id/result" element={<Result />} /><Route path="/history" element={<History />} /><Route path="/rules" element={<Rules />} /><Route path="/admin" element={<Admin />} /></Routes></main><footer>100ЛОТО · Игра с виртуальными очками <span>•</span> Версия 1.0</footer></div> }

