import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../shared/api/client';
import { ConfigEditor } from './ConfigEditor';

const number = (value: number) => Math.round(value).toLocaleString('ru-RU').replace(/ /g, ' ');

export function Admin() {
  const [tab, setTab] = useState<'config' | 'users' | 'audit'>('config');
  const users = useQuery({ queryKey: ['admin-users'], queryFn: api.adminUsers, enabled: tab === 'users' });
  const audit = useQuery({ queryKey: ['admin-audit'], queryFn: api.adminAudit, enabled: tab === 'audit' });
  const client = useQueryClient();

  const refreshAdminData = async () => {
    await client.invalidateQueries({ queryKey: ['admin-users'] });
    await client.invalidateQueries({ queryKey: ['admin-audit'] });
  };

  const toggle = async (id: string, blocked: boolean) => {
    const reason = window.prompt('Причина изменения статуса');
    if (!reason) return;
    await api.adminBlock(id, !blocked, reason);
    await refreshAdminData();
  };

  const adjust = async (id: string) => {
    const reason = window.prompt('Причина корректировки');
    if (!reason) return;
    const value = Number(window.prompt('Изменение баланса', '100') || 0);
    if (!value) return;
    await api.adminAdjust(id, value, 0, reason);
    await refreshAdminData();
  };

  return <section className="admin-workspace">
    <div className="section-title">
      <div>
        <span className="eyebrow">ЗОНА ЭКСПЕРТА</span>
        <h1>Админ-панель</h1>
        <p className="muted">Экономика, пользователи и аудит в одном рабочем пространстве.</p>
      </div>
      <span className="badge admin">ADMIN</span>
    </div>

    <div className="tabs">
      <button className={tab === 'config' ? 'active' : ''} onClick={() => setTab('config')}>Параметры игры</button>
      <button className={tab === 'users' ? 'active' : ''} onClick={() => setTab('users')}>Пользователи</button>
      <button className={tab === 'audit' ? 'active' : ''} onClick={() => setTab('audit')}>Журнал действий</button>
    </div>

    {tab === 'config' && <ConfigEditor />}

    {tab === 'users' && <div className="table admin-users">
      <div className="table-head"><span>Игрок</span><span>Роль</span><span>Баланс</span><span>Статус</span><span>Действия</span></div>
      {users.isLoading ? <div className="empty"><div className="spinner" /></div> : users.data?.map((user) => <div className="row" key={user.id}>
        <span><b>{user.username}</b><br /><small className="muted">уровень {user.level} · {user.points} XP</small></span>
        <span>{user.role}</span>
        <span className="num coin-value">{number(user.balance)} <img src="/assets/coin.png" alt="" /></span>
        <span className={user.blocked ? 'crash' : 'win'}>{user.blocked ? 'Заблокирован' : 'Аккаунт активен'}</span>
        <span className="admin-actions">
          <button className="ghost" onClick={() => toggle(user.id, user.blocked)}>{user.blocked ? 'Разблокировать' : 'Заблокировать'}</button>
          <button className="ghost" onClick={() => adjust(user.id)}>+ баланс</button>
        </span>
      </div>)}
    </div>}

    {tab === 'audit' && <div className="card">
      <span className="eyebrow">ЖУРНАЛ ДЕЙСТВИЙ</span>
      {audit.isLoading ? <div className="empty"><div className="spinner" /></div> : audit.data?.map((entry, index) => <div className="audit-item" key={index}>
        <div>
          <b>{entry.action}</b>
          <p className="muted">{entry.details}</p>
        </div>
        <time>{new Date(entry.createdAt).toLocaleString('ru-RU')}</time>
      </div>)}
      {!audit.isLoading && !audit.data?.length && <div className="empty"><span>✓</span><b>Здесь пока пусто</b></div>}
    </div>}
  </section>;
}
