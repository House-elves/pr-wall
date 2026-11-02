// pr-wall's tab on Elf Desk: the repo's open PRs, each with jev's answers about the change and its tests, as jev gave them.
// It runs in the desk's sandboxed frame: everything goes through `elf`, and only what elf.yaml declares.
import {
    LitElement, html, css, shared, cardStyles, spinner, ago, runTime, count, settingsButton, Ticking, elf,
} from '/elf-kit/1/kit.js';

const ID = /^(\d{4})-(\d{2})-(\d{2})_(\d{2})-(\d{2})-(\d{2})\.json$/;

function atOf(file) {
    const m = ID.exec(file);
    return m ? new Date(+m[1], m[2] - 1, +m[3], +m[4], +m[5], +m[6]) : null;
}

/** The saved runs, newest first: [{id, file, at}]. */
async function runs() {
    return (await elf.list('runs')).filter(f => ID.test(f.name)).slice(0, 20)
        .map(f => ({ id: f.name.slice(0, -5), file: f.name, at: atOf(f.name) }));
}

/** What you said about jev's answers: {"<number>/<question>": {verdict, ...}}. */
async function votes() {
    return (await elf.readJson('feedback', 'feedback.json'))?.votes ?? {};
}

// Each option's tone says what it means for a reviewer, so the bars read the same in every row.
const TONE = {
    all: 'good', some: 'part', none: 'bad', 'no-test-changes': 'bad', 'no-changed-code-reached': 'bad',
    'no-runtime-change': 'quiet', undetermined: 'unknown',
};
const STATUS = {
    draft: 'Draft: not asked', waiting: 'Waits for the next run', 'too-large': 'Too large to send',
    failed: "jev couldn't answer", 'dry-run': 'Not asked yet',
};
const p2 = p => (p ?? 0).toFixed(2);
const prob = (r, q, ...options) => options.reduce((s, o) => s + (r?.answers?.[q]?.probabilities?.[o] ?? 0), 0);

// Each sort reads one of jev's answers on its own: answers are never combined into a score.
/** The answer jev picked, and how sure it was of it. A yes/no answer picks yes from 0.5. */
function picked(q, a) {
    if (q.type === 'yes-no') {
        const p = a?.noul ?? 0;
        return { option: p >= 0.5 ? 'yes' : 'no', sure: Math.max(p, 1 - p) };
    }
    return { option: a?.choice, sure: a?.probabilities?.[a?.choice] ?? 0 };
}

const BANDS = [
    { label: '0.9 or more', from: 0.9, to: 2 },
    { label: '0.7 to 0.9', from: 0.7, to: 0.9 },
    { label: 'under 0.7', from: 0, to: 0.7 },
];

/**
 * How right jev has been, per question, from your votes on answers to the questions the wall asks now: in all, by how
 * sure it was (so you can see whether 0.9 means right nine times in ten), and what it said when it was wrong.
 */
function accuracy(votes, questions, asked) {
    const rows = questions.map(q => ({ q, judged: 0, right: 0, bands: BANDS.map(b => ({ ...b, judged: 0, right: 0 })), mixups: {} }));
    let older = 0;
    for (const [key, v] of Object.entries(votes ?? {})) {
        const row = rows.find(r => r.q.id === key.split('/')[1]);
        if (!v?.verdict || !row) continue;
        if (v.asked !== asked) {
            older++;
            continue;
        }
        const { option, sure } = picked(row.q, v.answer);
        const right = v.verdict === 'right';
        const band = row.bands.find(b => sure >= b.from && sure < b.to);
        row.judged++;
        band.judged++;
        if (right) {
            row.right++;
            band.right++;
        } else if (v.correct) {
            const k = `Said ${option}, was ${v.correct}`;
            row.mixups[k] = (row.mixups[k] ?? 0) + 1;
        }
    }
    return { rows, older, judged: rows.reduce((n, r) => n + r.judged, 0), right: rows.reduce((n, r) => n + r.right, 0) };
}

const ofCount = (right, judged) => judged ? html`${right} of ${judged} <span class="pct">${Math.round(100 * right / judged)}%</span>` : html`<span class="pct">none yet</span>`;

const SORTS = {
    updated: { label: 'Recently updated', key: pr => -Date.parse(pr.updatedAt || 0) },
    behaviour: { label: 'Most likely to change behaviour', key: pr => -(pr.result?.answers?.behaviour?.noul ?? -1) },
    reach: { label: 'Least reached by tests', key: pr => -prob(pr.result, 'reach', 'none', 'no-test-changes') },
    assert: { label: 'Least asserted', key: pr => -prob(pr.result, 'assert', 'none', 'no-test-changes', 'no-changed-code-reached') },
    weakens: { label: 'Most likely to weaken tests', key: pr => -(pr.result?.answers?.weakens?.noul ?? -1) },
};

class PrWallTab extends LitElement {

    static properties = {
        _runs: { state: true }, _picked: { state: true }, _run: { state: true }, _votes: { state: true },
        _loading: { state: true }, _error: { state: true }, _filter: { state: true }, _label: { state: true }, _kind: { state: true },
        _sort: { state: true }, _open: { state: true },
    };

    static styles = [shared, css`
        .page { display: grid; gap: 22px; }
        h1 { font-size: 34px; line-height: 1.15; font-weight: 600; letter-spacing: -0.022em; }
        .sub { margin: 6px 0 0; color: var(--muted); font-size: 14px; }
        .tools { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; }
        .tools input { flex: 1 1 14rem; min-width: 0; }
        ul { list-style: none; margin: 0; padding: 0; display: grid; gap: 10px; }
        li { padding: 14px 18px; display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 22rem); gap: 8px 22px; }
        @media (max-width: 760px) { li { grid-template-columns: minmax(0, 1fr); } }
        .title a { font-weight: 600; color: var(--ink); }
        .meta { font-size: 13px; color: var(--muted); display: flex; flex-wrap: wrap; gap: 4px 12px; margin-top: 4px; }
        .label { border: 1px solid var(--line); border-radius: 99px; padding: 0 8px; }
        .summary { margin: 8px 0 0; font-size: 14px; }
        .answers { display: grid; gap: 6px; font-size: 13px; align-content: start; }
        .answer { display: grid; grid-template-columns: 9.5rem minmax(0, 1fr); gap: 8px; align-items: center; }
        .answer .what { color: var(--muted); }
        .bar { display: flex; height: 10px; border-radius: 3px; overflow: hidden; background: var(--surface-2); }
        .bar span { height: 100%; }
        .good { background: var(--accent); }
        .part { background: color-mix(in srgb, var(--accent) 45%, var(--surface-2)); }
        .bad { background: var(--bad); }
        .quiet { background: var(--line); }
        .plain { background: color-mix(in srgb, var(--ink) 35%, var(--surface-2)); }
        .unknown { background: repeating-linear-gradient(45deg, var(--line) 0 3px, var(--surface-2) 3px 6px); }
        .chosen { font-size: 12px; color: var(--muted); grid-column: 2; margin-top: -3px; }
        .status { color: var(--muted); font-size: 13px; }
        .status.bad-text { color: var(--bad); }
        .more { grid-column: 1 / -1; justify-self: start; }
        .details { grid-column: 1 / -1; border-top: 1px solid var(--line); padding-top: 10px; display: grid; gap: 14px; font-size: 13px; }
        .details h3 { margin: 0 0 4px; font-size: 14px; }
        .details table { border-collapse: collapse; width: 100%; }
        .details td { padding: 3px 8px 3px 0; vertical-align: top; }
        .details td.p { font-variant-numeric: tabular-nums; white-space: nowrap; }
        .details tr.picked td { font-weight: 600; }
        .judge { display: flex; gap: 8px; align-items: center; margin-top: 6px; }
        .judge .said { color: var(--muted); }
        .empty { color: var(--muted); padding: 18px; margin: 0; }
        .accuracy { padding: 14px 20px; }
        .accuracy summary { cursor: pointer; font-weight: 600; }
        .accuracy table { border-collapse: collapse; margin: 12px 0 6px; font-size: 14px; }
        .accuracy th, .accuracy td { text-align: left; padding: 4px 18px 4px 0; vertical-align: top; }
        .accuracy th { font-weight: 500; color: var(--muted); font-size: 13px; }
        .accuracy .pct { color: var(--muted); }
        .accuracy .mixups { color: var(--muted); font-size: 13px; }
        .accuracy p { margin: 6px 0 0; color: var(--muted); font-size: 13px; max-width: 46rem; }
        .table-wrap { overflow-x: auto; }
        .key { padding: 16px 20px; display: flex; flex-wrap: wrap; gap: 10px 18px; align-items: center; justify-content: space-between; }
        .key p { margin: 0; max-width: 46rem; }
        .key strong { display: block; }
        .skeleton.big { height: 40vh; }
    `];

    constructor() {
        super();
        new Ticking(this);
        this._picked = null;
        this._loading = true;
        this._filter = '';
        this._label = '';
        this._kind = '';
        this._sort = 'updated';
        // The desk keeps it in this browser and answers later: until then, the default.
        elf.store.get('sort').then(v => { if (v in SORTS) this._sort = v; }).catch(() => {});
        this._open = new Set();
    }

    connectedCallback() {
        super.connectedCallback();
        this._load();
        this._off = elf.onChange(() => this._load());
    }

    disconnectedCallback() {
        this._off?.();
        super.disconnectedCallback();
    }

    async _load() {
        this._error = null;
        try {
            this._runs = await runs();
            const pick = this._runs.find(r => r.id === this._picked) ?? this._runs[0];
            this._run = pick ? { ...(await elf.readJson('runs', pick.file)), at: pick.at } : null;
            this._votes = await votes();
        } catch (e) {
            this._error = e.message;
        } finally {
            this._loading = false;
        }
    }

    _questions() {
        return (this._run?.checks ?? []).flatMap(c => c.questions ?? []);
    }

    async _judge(pr, question, verdict, correct) {
        const key = `${pr.number}/${question}`;
        const before = this._votes;
        // Shown at once; what the elf saved (with the answer it judged) is read back after.
        this._votes = { ...before, [key]: verdict === 'clear' ? undefined
            : { verdict, correct, asked: pr.result?.asked, answer: pr.result?.answers?.[question] } };
        try {
            await elf.act('judge', { number: pr.number, question, verdict, ...(correct ? { correct } : {}) });
            this._votes = await votes();
        } catch (e) {
            this._error = `Couldn't save that: ${e.message}`;
            this._votes = before;
        }
    }

    _accuracy() {
        const a = accuracy(this._votes, this._questions(), this._run?.asked);
        if (!a.judged && !a.older) return '';
        return html`<details class="panel accuracy">
            <summary>How right jev has been: ${a.judged ? html`${a.right} of ${a.judged} answers you judged` : 'nothing judged yet on the current questions'}</summary>
            <div class="table-wrap"><table>
                <tr><th>Question</th><th>Right</th>${BANDS.map(b => html`<th>When ${b.label} sure</th>`)}</tr>
                ${a.rows.map(r => html`<tr>
                    <td>${r.q.label}${Object.keys(r.mixups).length ? html`<div class="mixups">${Object.entries(r.mixups)
                        .sort((x, y) => y[1] - x[1]).map(([k, n]) => html`<div>${k}: ${n}</div>`)}</div>` : ''}</td>
                    <td>${ofCount(r.right, r.judged)}</td>
                    ${r.bands.map(b => html`<td>${ofCount(b.right, b.judged)}</td>`)}
                </tr>`)}
            </table></div>
            <p>A few votes say little: judge 20 or more answers to a question before reading much into its numbers.
                ${a.older ? `${count(a.older, 'vote')} on answers to earlier questions or another model ${a.older === 1 ? "isn't" : "aren't"} counted.` : ''}</p>
        </details>`;
    }

    _bar(q, a) {
        if (q.type === 'yes-no') {
            const p = a?.noul ?? 0;
            return html`<div class="bar" title="P(yes) ${p2(p)}"><span class=${q.yesTone ?? 'good'} style="width:${p * 100}%"></span></div>
                <span class="chosen">${p2(p)}</span>`;
        }
        const probs = a?.probabilities ?? {};
        // Options that are neither good nor bad for a reviewer (a kind of change): one plain bar for the one jev picked.
        if (!Object.keys(q.options).some(o => o in TONE)) {
            return html`<div class="bar" title=${Object.keys(q.options).map(o => `${o} ${p2(probs[o])}`).join(', ')}>
                    <span class="plain" style="width:${(probs[a?.choice] ?? 0) * 100}%"></span></div>
                <span class="chosen">${a?.choice} ${p2(probs[a?.choice])}</span>`;
        }
        return html`<div class="bar" title=${Object.keys(q.options).map(o => `${o} ${p2(probs[o])}`).join(', ')}>
                ${Object.keys(q.options).map(o => html`<span class=${TONE[o] ?? 'quiet'} style="width:${(probs[o] ?? 0) * 100}%"></span>`)}
            </div>
            <span class="chosen">${a?.choice} ${p2(probs[a?.choice])}${a?.confidence != null ? `, confidence ${p2(a.confidence)}` : ''}</span>`;
    }

    _details(pr) {
        const r = pr.result;
        return html`<div class="details">
            ${this._questions().map(q => {
                const a = r.answers?.[q.id];
                const vote = this._votes?.[`${pr.number}/${q.id}`];
                const said = vote?.verdict;
                const rows = q.type === 'yes-no' ? [['yes', q.yes, a?.noul], ['no', q.no, 1 - (a?.noul ?? 0)]]
                    : Object.entries(q.options).map(([o, d]) => [o, d, a?.probabilities?.[o]]);
                const chosen = picked(q, a).option;
                return html`<section>
                    <h3>${q.text}</h3>
                    <table>${rows.map(([o, d, p]) => html`<tr class=${o === chosen ? 'picked' : ''}>
                        <td class="p">${p2(p)}</td><td>${o}</td><td>${d}</td></tr>`)}</table>
                    <div class="judge">
                        ${said ? html`<span class="said">You said ${said}${vote.correct ? `: it's ${vote.correct}` : ''}.</span>
                                <button class="quiet" @click=${() => this._judge(pr, q.id, 'clear')}>Take back</button>`
                            : html`<span class="said">Is jev right?</span>
                                <button class="quiet" @click=${() => this._judge(pr, q.id, 'right')}>Right</button>
                                <button class="quiet" @click=${() => this._judge(pr, q.id, 'wrong')}>Wrong</button>`}
                    </div>
                    ${said === 'wrong' && !vote.correct ? html`<div class="judge">
                        <span class="said">What's right?</span>
                        ${(q.type === 'yes-no' ? ['yes', 'no'] : Object.keys(q.options)).filter(o => o !== chosen).map(o => html`
                            <button class="quiet" @click=${() => this._judge(pr, q.id, 'wrong', o)}>${o}</button>`)}
                    </div>` : ''}
                </section>`;
            })}
            <p class="sub">${count(r.files ?? 0, 'file')}, ${count(r.testFiles ?? 0, 'test file')}, ${(r.chars ?? 0).toLocaleString()} characters sent
                ${r.omitted?.length ? html`, leaving out ${r.omitted.join(', ')}` : ''}.
                Asked ${r.at ? ago(new Date(r.at)) : ''} about ${(r.sha ?? '').slice(0, 7)}, with ${r.model}${r.usage?.input_tokens ? `, ${r.usage.input_tokens.toLocaleString()} tokens` : ''}.</p>
        </div>`;
    }

    _pr(pr) {
        const r = pr.result ?? {};
        const answered = !!r.answers;
        const open = this._open.has(pr.number);
        return html`<li class="panel">
            <div>
                <div class="title">${pr.url ? html`<a href=${pr.url}>#${pr.number} ${pr.title}</a>` : html`<strong>#${pr.number} ${pr.title}</strong>`}</div>
                <div class="meta">
                    <span>${pr.author}</span>
                    ${pr.updatedAt ? html`<span>updated ${ago(new Date(pr.updatedAt))}</span>` : ''}
                    <span>+${pr.additions} −${pr.deletions}</span>
                    ${(pr.labels ?? []).map(l => html`<span class="label">${l}</span>`)}
                </div>
                ${r.summary ? html`<p class="summary">${r.summary}</p>` : ''}
            </div>
            <div class="answers">
                ${r.status !== 'classified' && r.status !== 'needs-key' ? html`<span class="status ${r.status === 'failed' ? 'bad-text' : ''}">
                    ${STATUS[r.status] ?? r.status}${r.stale ? ' (below: an older commit)' : ''}${r.why ? `: ${r.why}` : ''}</span>` : ''}
                ${answered ? this._questions().map(q => html`<div class="answer"><span class="what">${q.label}</span>${this._bar(q, r.answers[q.id])}</div>`) : ''}
            </div>
            ${answered ? html`<button class="quiet more" aria-expanded=${open}
                @click=${() => { const s = new Set(this._open); open ? s.delete(pr.number) : s.add(pr.number); this._open = s; }}>
                ${open ? 'Less' : 'Every answer'}</button>` : ''}
            ${answered && open ? this._details(pr) : ''}
        </li>`;
    }

    render() {
        const r = this._run;
        const all = r?.prs ?? [];
        const labels = [...new Set(all.flatMap(p => p.labels ?? []))].sort();
        const kinds = Object.keys(this._questions().find(q => q.id === 'kind')?.options ?? {})
            .filter(k => all.some(p => p.result?.answers?.kind?.choice === k));
        const f = this._filter.trim().toLowerCase();
        const shown = all
            .filter(p => !this._label || (p.labels ?? []).includes(this._label))
            .filter(p => !this._kind || p.result?.answers?.kind?.choice === this._kind)
            .filter(p => !f || `#${p.number} ${p.title} ${p.author} ${(p.labels ?? []).join(' ')}`.toLowerCase().includes(f))
            .sort((a, b) => (SORTS[this._sort] ?? SORTS.updated).key(a) - (SORTS[this._sort] ?? SORTS.updated).key(b));
        return html`
            <div class="page">
                <div class="page-top"><div>
                    <h1>PR wall</h1>
                    <p class="sub">${r ? `${count(all.length, 'open PR')} in ${r.repo}, refreshed ${runTime(r.at).toLowerCase()}.${r.needsKey ? '' : ` ${r.stats}.`}`
                        : this._loading ? '' : 'Nothing read yet.'}</p>
                </div>${settingsButton(null, 'PR wall settings')}</div>
                <elf-run-bar command="refresh" label="Refresh the wall" .runs=${(this._runs ?? []).map(x => ({ id: x.id, label: runTime(x.at) }))}
                    .picked=${this._picked} note="Asks jev about PRs with new commits. Never writes to GitHub."
                    @run-picked=${e => { this._picked = e.detail; this._load(); }}
                    @run-finished=${() => { this._picked = null; this._load(); }}></elf-run-bar>
                ${this._error ? html`<p class="error" role="alert">${this._error}</p>` : ''}
                ${this._accuracy()}
                ${r?.needsKey ? html`<div class="panel key">
                    <p><strong>Add your TypeSafe API key to see what jev says about these PRs.</strong>
                        Until then the wall lists them without answers. The next refresh after you add it asks jev.</p>
                    <button @click=${() => elf.settings(['TYPESAFE_API_KEY'], 'TypeSafe API key')}>Add the API key</button>
                </div>` : ''}
                ${this._loading && !r ? html`<div class="skeleton big"></div>` : ''}
                ${all.length ? html`<div class="tools">
                    <input type="search" placeholder="Filter by title, author, label or number" aria-label="Filter"
                        .value=${this._filter} @input=${e => { this._filter = e.target.value; }}>
                    <select aria-label="Label" @change=${e => { this._label = e.target.value; }}>
                        <option value="">Every label</option>
                        ${labels.map(l => html`<option .selected=${l === this._label}>${l}</option>`)}
                    </select>
                    ${kinds.length ? html`<select aria-label="Kind" @change=${e => { this._kind = e.target.value; }}>
                        <option value="">Every kind</option>
                        ${kinds.map(k => html`<option .selected=${k === this._kind}>${k}</option>`)}
                    </select>` : ''}
                    <select aria-label="Sort" @change=${e => { this._sort = e.target.value; elf.store.set('sort', this._sort).catch(() => {}); }}>
                        ${Object.entries(SORTS).map(([k, s]) => html`<option value=${k} .selected=${k === this._sort}>${s.label}</option>`)}
                    </select>
                </div>` : ''}
                ${r ? (shown.length ? html`<ul>${shown.map(p => this._pr(p))}</ul>`
                        : html`<p class="panel empty">${all.length ? 'No PR matches the filter.' : 'The repo has no open PRs.'}</p>`)
                    : !this._loading ? html`<p class="panel empty">Refresh the wall to list the open PRs.</p>` : ''}
            </div>
        `;
    }
}

/** The dashboard card: one sentence on the wall. It never asks for attention: nothing here is yours to do. */
class PrWallCard extends LitElement {

    static properties = { _run: { state: true }, _state: { state: true }, _loaded: { state: true } };
    static styles = [shared, cardStyles];

    constructor() {
        super();
        new Ticking(this);
    }

    connectedCallback() {
        super.connectedCallback();
        this._load();
        this._off = elf.onChange(() => this._load());
    }

    disconnectedCallback() {
        this._off?.();
        super.disconnectedCallback();
    }

    async _load() {
        try {
            const [newest] = await runs();
            this._run = newest ? { ...(await elf.readJson('runs', newest.file)), at: newest.at } : null;
            this._state = await elf.state();
        } catch (e) {
            this._run = null;
        }
        this._loaded = true;
        elf.card({ attention: false });
    }

    render() {
        if (!this._loaded) return html`<div class="skeleton say-sk"></div>`;
        const prs = this._run?.prs ?? [];
        const asked = prs.filter(p => p.result?.answers).length;
        const say = this._run?.needsKey ? html`<a href="#">${count(prs.length, 'open PR')}</a> in ${this._run.repo}. Add your TypeSafe API key to classify them.`
            : this._run ? html`<a href="#">${count(prs.length, 'open PR')}</a> in ${this._run.repo}, ${asked} classified.`
            : html`Nothing read yet. <a href="#">Refresh the wall</a>.`;
        const running = this._state?.running?.refresh;
        return html`<p class="say">${say}</p>
            <div class="meta">${running ? html`<span class="running" role="status">${spinner}Refreshing, started ${ago(running)}</span>`
                : this._run ? html`<span>Refreshed ${runTime(this._run.at)}</span>` : ''}</div>`;
    }
}

customElements.define('pr-wall-tab', PrWallTab);
customElements.define('pr-wall-card', PrWallCard);
document.body.append(document.createElement(elf.view === 'card' ? 'pr-wall-card' : 'pr-wall-tab'));
