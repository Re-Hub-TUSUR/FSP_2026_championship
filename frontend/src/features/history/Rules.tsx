import { useState } from 'react';
import { Link } from 'react-router-dom';

const entities = [
  { icon: '⛃', name: 'Бонусы', role: 'Валюта ставки и выигрыша', how: 'Старт (500), сбор пазлов, победа в раунде' },
  { icon: '⚡', name: 'Очки', role: 'Продвижение в Турнире', how: 'Начисляются за каждый пройденный уровень' },
  { icon: '🧩', name: 'Пазлы', role: 'Коллекция наград', how: '1 фрагмент за раунд (собери 6 шт. → получи +250 бонусов)' }
];
const mechanics = [
  { icon: '🚀', title: 'Полёт и Cashout', summary: 'Забрать можно с 1-го уровня.', detail: 'После нажатия «Забрать» сумма фиксируется по формуле ставки и текущего коэффициента, но шар продолжает полёт до краша.' },
  { icon: '🎁', title: 'Бустеры пазла', summary: 'Множитель ×2, ×3 или ×4 ждёт на случайной линии.', detail: 'Если шар преодолевает линию до Cashout, срабатывает формула: Итоговый множитель = Текущий × Бустер (×2, ×3 или ×4). Вы также получаете экстра-очки.' },
  { icon: '💥', title: 'Взрыв (Crash)', summary: 'Шар может лопнуть на любой линии.', detail: 'Если шар лопнул до вывода, ставка сгорает, но заработанные за раунд турнирные очки остаются в общем зачёте.' },
  { icon: '🛡️', title: 'Честность (Provably Fair)', summary: 'Результат раунда формирует сервер.', detail: 'Хеш SHA-256 генерируется до старта раунда и доступен для проверки в истории игр — точку взрыва нельзя изменить задним числом.' }
];
export function Rules() {
  const [active, setActive] = useState<number | null>(null);
  return <section className="rules-guide">
    <header className="rules-guide-head"><div><span className="eyebrow">ВСЁ ПРОСТО</span><h1>Как играть</h1><p>Разберитесь в экономике, риске и наградах за одну минуту.</p></div><div className="rules-guide-hint">💡 <span>Ставьте бонусы, набирайте очки<br/>и забирайте выигрыш вовремя.</span></div></header>
    <section className="rules-block"><div className="rules-block-title"><span>ШАГ 1</span><h2>Выберите свой шар</h2></div><div className="rules-balloons"><article className="rules-balloon red"><img src="/art/balloon-red.svg" alt="Красный шар"/><div><h3>🔴 Красный шар</h3><p>12 уровней высоты</p><p>Высокий риск краха</p><p>Максимальный множитель</p></div></article><article className="rules-balloon green"><img src="/art/balloon-green.svg" alt="Зелёный шар"/><div><h3>🟢 Зелёный шар</h3><p>9 уровней высоты</p><p>Плавный рост коэффициента</p><p>Для аккуратной стратегии</p></div></article></div></section>
    <section className="rules-block"><div className="rules-block-title"><span>ШАГ 2</span><h2>Три игровые сущности</h2><p>Не путайте валюту, рейтинг и коллекционные награды.</p></div><div className="entity-table"><div className="entity-row entity-head"><span>Сущность</span><span>Зачем нужна</span><span>Как получить</span></div>{entities.map((item) => <div className="entity-row" key={item.name}><strong><i>{item.icon}</i>{item.name}</strong><span>{item.role}</span><span>{item.how}</span></div>)}</div></section>
    <section className="rules-block"><div className="rules-block-title"><span>ШАГ 3</span><h2>Механика полёта</h2><p>Нажмите на карточку, чтобы раскрыть подробности.</p></div><div className="mechanics-grid">{mechanics.map((item, index) => <button type="button" className={`mechanic-card ${active === index ? 'open' : ''}`} key={item.title} onClick={() => setActive(active === index ? null : index)}><span className="mechanic-icon">{item.icon}</span><span className="mechanic-copy"><strong>{item.title}</strong><span>{item.summary}</span>{active === index && <span className="mechanic-detail">{item.detail}</span>}</span><span className="mechanic-toggle">{active === index ? '−' : '+'}</span></button>)}</div></section>
    <section className="economy-note"><div className="economy-icon">⛃</div><div><strong>Формула раунда</strong><p>Ставка × Текущий коэффициент</p></div></section><div className="rules-cta-wrap"><Link className="rules-cta" to="/play">🚀 Понятно, погнали играть!</Link></div>
  </section>;
}
