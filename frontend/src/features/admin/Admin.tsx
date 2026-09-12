import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../shared/api/client';
import { ConfigEditor } from './ConfigEditor';

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
    <div className="section-title page-title">
      <div>
        <span className="eyebrow">CONTROL CENTER</span>
        <h1>Панель управления</h1>
        <p className="muted">Экономика, пользователи и аудит в одном рабочем пространстве.</p>
      </div>
      <span className="live-pill"><i /> ADMIN</span>
    </div>

    <div className="tabs admin-tabs">
      <button className={tab === 'config' ? 'active' : ''} onClick={() => setTab('config')}>Параметры игры</button>
      <button className={tab === 'users' ? 'active' : ''} onClick={() => setTab('users')}>Пользователи</button>
      <button className={tab === 'audit' ? 'active' : ''} onClick={() => setTab('audit')}>Журнал действий</button>
    </div>

    {tab === 'config' && <ConfigEditor />}

    {tab === 'users' && <div className="card admin-card">
      <div className="table admin-users">
        <div className="row table-head"><span>Пользователь</span><span>Роль</span><span>Баланс</span><span>Статус</span><span>Действия</span></div>
        {users.isLoading ? <div className="loading">Загрузка…</div> : users.data?.map((user) => <div className="row" key={user.id}>
          <span><b>{user.username}</b><small>уровень {user.level} · {user.points} XP</small></span>
          <span>{user.role}</span>
          <span>{user.balance} ★</span>
          <span className={user.blocked ? 'crash' : 'win'}>{user.blocked ? 'Заблокирован' : 'Активен'}</span>
          <span className="admin-actions">
            <button onClick={() => toggle(user.id, user.blocked)}>{user.blocked ? 'Разблокировать' : 'Заблокировать'}</button>
            <button onClick={() => adjust(user.id)}>+ баланс</button>
          </span>
        </div>)}
      </div>
    </div>}

    {tab === 'audit' && <div className="card admin-card">
      <div className="audit-list">
        {audit.isLoading ? <div className="loading">Загрузка журнала…</div> : audit.data?.map((entry, index) => <div className="audit-item" key={index}>
          <span className="dot green" />
          <div><b>{entry.action}</b><p>{entry.details}</p></div>
          <time>{new Date(entry.createdAt).toLocaleString('ru-RU')}</time>
        </div>)}
      </div>
    </div>}
  </section>;
}
