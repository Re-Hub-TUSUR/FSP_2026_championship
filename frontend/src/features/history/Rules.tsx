import { useState } from 'react';
import { Link } from 'react-router-dom';

const steps = [
  { n: '01', title: 'Выберите шар', text: 'Красный — 12 уровней и максимальный множитель, зелёный — 9 уровней и плавный подъём.' },
  { n: '02', title: 'Поставьте бонусы', text: 'Номинал ставки задаёт и уровень бустера: чем он выше, тем реже выпадает.' },
  { n: '03', title: 'Заберите вовремя', text: 'Фиксируйте выигрыш до взрыва. После фиксации шар летит дальше — видно, сколько вы могли бы взять.' }
];

const entities = [
  { name: 'Бонусы', role: 'Валюта ставки и выигрыша', how: 'Старт (500), сбор пазлов, победа в раунде' },
  { name: 'Очки', role: 'Продвижение в турнире', how: 'Начисляются за каждый пройденный уровень' },
  { name: 'Пазлы', role: 'Коллекция наград', how: '1 фрагмент за раунд, 6 фрагментов → лотерейный билет' }
];

const mechanics = [
  { title: 'Полёт и фиксация', summary: 'Первый подъём без риска, забрать можно со второго уровня.', detail: 'После нажатия «Забрать» сумма фиксируется по ставке и текущему коэффициенту, но шар продолжает полёт до взрыва.' },
  { title: 'Бустеры пазла', summary: 'Множитель ×2, ×3 или ×4 ждёт на случайном уровне начиная с третьего.', detail: 'Если шар преодолевает уровень бустера до фиксации, итоговый множитель умножается. Возврат игроку при этом одинаков для всех номиналов.' },
  { title: 'Взрыв', summary: 'Начиная со второго уровня шар может лопнуть на любом шаге.', detail: 'Если шар лопнул до фиксации, ставка сгорает, но заработанные за раунд турнирные очки остаются в общем зачёте.' },
  { title: 'Честность раунда', summary: 'Точка взрыва определяется до старта.', detail: 'Хеш SHA-256 публикуется до начала раунда и доступен для проверки в истории игр — результат нельзя изменить задним числом.' }
];

export function Rules() {
  const [active, setActive] = useState<number | null>(null);

  return <div className="play-layout">
    <section className="rules-layout">
      <div>
        <span className="eyebrow">ВСЁ ПРОСТО</span>
        <h1>Как играть</h1>
        <p className="muted">Разберитесь в экономике, риске и наградах за одну минуту.</p>
        <div className="rules-steps">
          {steps.map((step) => <div key={step.n}>
            <b>{step.n}</b>
            <span><strong>{step.title}</strong><br /><small className="muted">{step.text}</small></span>
          </div>)}
        </div>
      </div>

      <div className="card rules-card">
        <span className="eyebrow">ТРИ ИГРОВЫЕ СУЩНОСТИ</span>
        <h2>Не путайте валюту, рейтинг и награды</h2>
        {entities.map((item) => <article key={item.name}>
          <h3>{item.name}</h3>
          <p className="muted">{item.role} · {item.how}</p>
        </article>)}
      </div>
    </section>

    <section className="setup-card">
      <div className="section-title">
        <div><span className="eyebrow">МЕХАНИКА ПОЛЁТА</span><h2>Как устроен раунд</h2></div>
        <span className="muted">Нажмите на карточку, чтобы раскрыть подробности</span>
      </div>
      <div className="theme-grid">
        {mechanics.map((item, index) => <button
          type="button"
          key={item.title}
          className={`theme-option ${active === index ? 'selected' : ''}`}
          onClick={() => setActive(active === index ? null : index)}
        >
          <span>
            <b>{item.title}</b>
            <small className="muted">{active === index ? item.detail : item.summary}</small>
          </span>
        </button>)}
      </div>
    </section>

    <section className="setup-card">
      <div className="section-title">
        <div><span className="eyebrow">ВЫБЕРИТЕ СВОЙ ШАР</span><h2>Две стратегии риска</h2></div>
      </div>
      <div className="theme-grid">
        <div className="theme-option">
          <img src="/assets/balloon-red.png" alt="Красный шар" />
          <span><b>Красный шар</b><small className="muted">12 уровней · высокий риск · максимальный множитель</small></span>
        </div>
        <div className="theme-option">
          <img src="/assets/balloon-green.png" alt="Зелёный шар" />
          <span><b>Зелёный шар</b><small className="muted">9 уровней · плавный рост · для аккуратной стратегии</small></span>
        </div>
      </div>
      <div className="start-row">
        <span className="muted">Формула раунда: ставка × текущий коэффициент</span>
        <Link className="primary" to="/play">Понятно, играем <span aria-hidden="true">→</span></Link>
      </div>
    </section>
  </div>;
}
