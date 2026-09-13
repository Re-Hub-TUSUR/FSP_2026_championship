import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { api, isDemoMode, type LeaderRow, type Tournament } from '../../shared/api/client';

const number = (value: number) => new Intl.NumberFormat('ru-RU').format(Math.round(value || 0)).replace(/ /g, ' ');
const maskName = (name: string) => (name.length <= 3 ? '***' : `***${name.slice(3)}`);
const pad = (value: number) => String(value).padStart(2, '0');

const demoPlayers: LeaderRow[] = [
  ['demo-airlex', 'airlex', 3420, 12], ['demo-victor', 'victor', 2100, 11], ['demo-skyplayer', 'skyplayer', 1850, 10],
  ['demo-winner', 'abcwinner', 1420, 10], ['demo-captain', 'abccaptain', 1100, 9], ['demo-lucky', 'abclucky', 940, 8],
  ['demo-ivan', 'abcivan', 850, 8], ['demo-alex', 'abcalex', 760, 7], ['demo-rain', 'abcpilot', 650, 7],
  ['demo-nova', 'abcnova', 560, 6], ['demo-star', 'abcstar', 500, 6], ['demo-cloud', 'abccloud', 450, 5],
  ['demo-wind', 'abcwind', 390, 5], ['demo-flame', 'abcflame', 340, 4], ['demo-orbit', 'abcorbit', 290, 4],
  ['demo-wing', 'abcwing', 240, 3], ['demo-solo', 'abcsolo', 180, 2], ['demo-kite', 'abckite', 125, 2]
].map(([id, username, points, level], index) => ({
  id: String(id), username: String(username), points: Number(points), level: Number(level),
  balance: 0, multiplier: 1 + (18 - index) * 0.03, trend: index % 4 === 0 ? 2 : index % 5 === 0 ? -1 : 0, rounds: 8 + index
}));
const fallbackPlayers: LeaderRow[] = [
  { id: 'demo-admin', username: 'admin', points: 50, level: 1, balance: 0, multiplier: 1, trend: 0, rounds: 2 },
  { id: 'demo-current', username: 'demo', points: 20, level: 1, balance: 0, multiplier: 1, trend: 0, rounds: 1 }
];

/** Призы топ-3 приходят с сервера турниров; без него — резервные значения. */
const prizesOf = (tournament?: Tournament) =>
  tournament ? [tournament.firstPrize, tournament.secondPrize, tournament.thirdPrize] : [1000, 500, 250];

/** Отсчёт до конца турнира по серверному endsAt; если сервис недоступен — резервные 14 дней. */
function useTournamentClock(tournament?: Tournament) {
  const [now, setNow] = useState(() => Date.now());
  const [fallbackEnd] = useState(() => Date.now() + 14 * 24 * 60 * 60 * 1000);
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);
  const serverEnd = tournament?.endsAt ? new Date(tournament.endsAt).getTime() : NaN;
  const left = Math.max(0, Math.floor(((Number.isFinite(serverEnd) ? serverEnd : fallbackEnd) - now) / 1000));
  return {
    active: (tournament ? tournament.active : true) && left > 0,
    days: Math.floor(left / 86400),
    hours: Math.floor((left % 86400) / 3600),
    minutes: Math.floor((left % 3600) / 60),
    seconds: left % 60
  };
}

export function Leaderboard() {
  const [search, setSearch] = useState('');
  const q = useQuery({ queryKey: ['leaderboard'], queryFn: api.leaderboard, refetchInterval: 10000, refetchIntervalInBackground: true, retry: false });
  const me = useQuery({ queryKey: ['me'], queryFn: api.me, staleTime: 60000, retry: false });
  const tournamentQuery = useQuery({ queryKey: ['tournament'], queryFn: api.tournament, refetchInterval: 60000, retry: false });
  const tournament = tournamentQuery.data;
  const clock = useTournamentClock(tournament);
  const prizes = prizesOf(tournament);
  const currentName = me.data?.username;

  // Демонстрационный состав подмешивается только в демо-режиме или когда рейтинг недоступен:
  // иначе выдуманные игроки занимали бы места и призы настоящего турнира.
  const showDemo = isDemoMode() || q.isError;
  const players = useMemo(() => {
    const merged = [...(q.data || []), ...(showDemo ? [...demoPlayers, ...fallbackPlayers] : [])]
      .filter((row, index, list) => list.findIndex((candidate) => candidate.id === row.id || candidate.username === row.username) === index);
    if (me.data && !merged.some((row) => row.id === me.data?.id || row.username === me.data?.username)) {
      merged.push({ id: me.data.id || 'current-player', username: me.data.username, points: me.data.points || 0, level: me.data.level || 1, balance: me.data.balance, multiplier: 1, trend: 0, rounds: 0 });
    }
    return merged.sort((left, right) => right.points - left.points || left.username.localeCompare(right.username, 'ru'));
  }, [q.data, me.data, showDemo]);

  const isMe = (row: LeaderRow) => row.id === me.data?.id || row.username === currentName;
  const rankOf = (row: LeaderRow) => players.indexOf(row) + 1;
  const nameOf = (row: LeaderRow) => (isMe(row) ? row.username : maskName(row.username));
  const prizeFor = (rank: number) => (rank <= 3 ? `${number(prizes[rank - 1])} ★` : '—');

  const podium = players.slice(0, 3);
  const rows = useMemo(() => {
    const needle = search.trim().toLocaleLowerCase('ru-RU');
    return players.slice(3).filter((row) => !needle || row.username.toLocaleLowerCase('ru-RU').includes(needle));
  }, [players, search]);

  const current = currentName ? players.find(isMe) : undefined;
  const currentRank = current ? rankOf(current) : undefined;
  const topTenGap = current && currentRank && currentRank > 10 ? Math.max(0, (players[9]?.points || 0) - current.points) : 0;

  return <div className="play-layout">
    <section className="tournament-hero">
      <span className="eyebrow">ЛИГА 100ЛОТО · ТУРНИР</span>
      <h1>Кубок воздухоплавателей</h1>
      <p>Призовой фонд <b>{number(prizes.reduce((sum, prize) => sum + prize, 0))} ★</b> делят три лидера рейтинга</p>
      <div className="tournament-hero-row">
        <div className="tournament-prizes">
          {prizes.map((prize, index) => <span key={index}>
            <img src={`/assets/medal-${index + 1}.png`} alt="" width={28} height={28} />
            <b>{index + 1} место</b> {number(prize)} ★
          </span>)}
        </div>
        {clock.active ? <div className="tournament-clock">
          <small>ДО ПОДВЕДЕНИЯ ИТОГОВ</small>
          <div className="tournament-countdown">
            <span><b>{clock.days}</b><small>дни</small></span>
            <span><b>{pad(clock.hours)}</b><small>часы</small></span>
            <span><b>{pad(clock.minutes)}</b><small>минуты</small></span>
            <span><b>{pad(clock.seconds)}</b><small>секунды</small></span>
          </div>
        </div> : <div className="tournament-clock">
          <b>Турнир завершён</b>
          <small>{tournament?.prizesPaid ? 'ПРИЗЫ ВЫПЛАЧЕНЫ' : 'ПОДВОДИМ ИТОГИ'}</small>
        </div>}
      </div>
    </section>

    {current && currentRank && <section className="leader-personal has-cta">
      <div className="leader-personal-main">
        <span className="leader-avatar">{current.username.slice(0, 1).toUpperCase()}</span>
        <div><span className="eyebrow">ВАША ПОЗИЦИЯ</span><b className="num">#{currentRank}</b><small className="muted">{current.username}</small></div>
      </div>
      <div><span className="eyebrow">УРОВЕНЬ</span><b className="num">{current.level}</b></div>
      <div><span className="eyebrow">ОЧКИ</span><b className="num">{number(current.points)}</b></div>
      <div><span className="eyebrow">ДО ТОП-10</span><b className="num">{topTenGap ? `${number(topTenGap)} очков` : 'вы в ТОП-10'}</b></div>
      <Link className="primary" to="/play">В полёт <span aria-hidden="true">→</span></Link>
    </section>}

    {q.isLoading ? <div className="card"><div className="spinner" /></div> : <>
      <section className="leader-podium" aria-label="Пьедестал почёта">
        {podium.map((row, index) => <article className={`podium-card podium-card-${index + 1}`} key={row.id}>
          <img className="laurel left" src="/assets/laurel-left.png" alt="" />
          <img className="laurel right" src="/assets/laurel-right.png" alt="" />
          <img src={`/assets/medal-${index + 1}.png`} alt="" width={64} height={64} />
          <small className="eyebrow">{index + 1} МЕСТО</small>
          <b>{nameOf(row)}</b>
          {isMe(row) && <span className="badge admin">ВЫ</span>}
          <div className="podium-score">{number(row.points)} <small className="muted">очков</small></div>
          <span className="podium-prize">+{prizeFor(index + 1)}</span>
        </article>)}
      </section>

      <section className="leader-table is-tournament">
        <div className="leader-table-top">
          <div><h2>Все участники</h2><small className="muted">Места с 4-го и ниже</small></div>
          <span className="muted">{players.length} участников</span>
        </div>
        <label className="search">
          <span aria-hidden="true">⌕</span>
          <input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Поиск по никнейму" aria-label="Поиск по никнейму" />
        </label>
        {q.isError && <p className="tournament-note">Показываем демонстрационный состав: рейтинг временно недоступен.</p>}
        {tournamentQuery.isError && <p className="tournament-note">Срок и призы показаны по резервным настройкам: сервис турниров временно недоступен.</p>}
        <div className="leader-table-head"><span>#</span><span>Игрок</span><span>Уровень</span><span>Очки</span><span>Возможный приз</span></div>
        <div className="leader-table-scroll">
          {rows.map((row) => <div className={`leader-table-row ${isMe(row) ? 'current' : ''}`} key={row.id}>
            <span className="num">#{rankOf(row)}</span>
            <span className="tournament-player">
              <span className="leader-avatar">{row.username.slice(0, 1).toUpperCase()}</span>
              <b>{nameOf(row)}</b>
              {isMe(row) && <span className="badge admin">ВЫ</span>}
            </span>
            <span>{row.level} ур.</span>
            <b className="num">{number(row.points)}</b>
            <span className="possible-prize">{prizeFor(rankOf(row))}</span>
          </div>)}
          {!rows.length && <div className="empty">
            <span>⌕</span>
            <b>Никого не нашли</b>
            <p className="muted">Измените запрос и попробуйте снова.</p>
          </div>}
        </div>
      </section>
    </>}
  </div>;
}
