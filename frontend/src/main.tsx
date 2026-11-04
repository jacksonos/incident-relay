import React, { useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import axios from 'axios';
import incident from '../../fixtures/incident.log?raw';
import normal from '../../fixtures/normal.log?raw';
import './style.css';

type Level = 'DEBUG' | 'INFO' | 'WARN' | 'ERROR';
type Event = { id: string; lineNumber: number; timestampOriginal: string; timestampUtc: string; level: Level; service: string; message: string; rawLine: string };
type Result = { analysis: { events: Event[]; invalidLineNumbers: number[]; blankLineCount: number; warnings: string[]; summary: { processedCount: number; rejectedCount: number; firstEvent: string; lastEvent: string; levelCounts: Record<Level, number> }; groups: { normalizedMessage: string; eventIds: string[] }[] }; postmortemMarkdown: string };
const levels: Level[] = ['DEBUG', 'INFO', 'WARN', 'ERROR'];
const limit = 1024 * 1024;

function validate(text: string): string {
  if (!text.trim()) return 'La entrada está vacía.';
  if (new TextEncoder().encode(text).length > limit) return 'La entrada supera 1 MiB en UTF-8.';
  if (text.split(/\r\n|\r|\n/).filter(line => line.trim()).length > 10000) return 'La entrada supera 10 000 líneas no vacías.';
  return '';
}

function App() {
  const [source, setSource] = useState('texto');
  const [text, setText] = useState('');
  const [result, setResult] = useState<Result | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [level, setLevel] = useState('');
  const [search, setSearch] = useState('');
  const [selected, setSelected] = useState<Event | null>(null);
  const [draft, setDraft] = useState(false);
  const generation = useRef(0);
  const request = useRef<AbortController | null>(null);

  function reset() {
    generation.current++;
    request.current?.abort();
    request.current = null;
    setBusy(false); setResult(null); setError(''); setSelected(null); setDraft(false); setLevel(''); setSearch('');
  }
  function changeSource(value: string) {
    reset(); setSource(value); setText(value === 'incidente' ? incident : value === 'normal' ? normal : '');
  }
  async function loadFile(file?: File) {
    reset(); setText('');
    if (!file) return;
    if (!/\.(log|txt)$/i.test(file.name)) { setError('Selecciona un archivo .log o .txt.'); return; }
    if (file.size > limit) { setError('La entrada supera 1 MiB en UTF-8.'); return; }
    const current = generation.current;
    setBusy(true);
    try {
      const bytes = await file.arrayBuffer();
      const value = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(bytes);
      if (current !== generation.current) return;
      const issue = validate(value);
      if (issue) setError(issue); else setText(value);
    } catch {
      if (current === generation.current) setError('No se pudo leer el archivo como UTF-8 válido.');
    } finally { if (current === generation.current) setBusy(false); }
  }
  async function analyze() {
    reset();
    const issue = validate(text);
    if (issue) { setError(issue); return; }
    const current = generation.current;
    const controller = new AbortController(); request.current = controller; setBusy(true);
    try {
      const response = await axios.post<Result>('/api/analyze', { text }, { signal: controller.signal, timeout: 15000 });
      if (current === generation.current) setResult(response.data);
    } catch (err) {
      if (current !== generation.current) return;
      const status = axios.isAxiosError(err) ? err.response?.status : undefined;
      setError(status === 400 ? 'Entrada inválida o sin eventos válidos. Revisa el formato.' : status === 413 ? 'La entrada supera los límites permitidos.' : 'No se pudo conectar con el servicio o se agotó el tiempo de espera.');
    } finally { if (current === generation.current) { setBusy(false); request.current = null; } }
  }
  function download() {
    if (!result) return;
    const url = URL.createObjectURL(new Blob([result.postmortemMarkdown], { type: 'text/markdown;charset=utf-8' }));
    const link = document.createElement('a'); link.href = url; link.download = 'postmortem.md'; link.click(); URL.revokeObjectURL(url);
  }
  const analysis = result?.analysis;
  const filtered = analysis?.events.filter(e => (!level || e.level === level) && `${e.id} ${e.service} ${e.message} ${e.rawLine}`.toLocaleLowerCase().includes(search.toLocaleLowerCase())) ?? [];
  return <main>
    <header>
      <p className="eyebrow">HERRAMIENTA LOCAL · MODO DETERMINISTA</p>
      <h1>Incident Replay</h1>
      <p>Reconstruye una cronología a partir de logs y revisa la evidencia.</p>
    </header>
    <aside className="notice">
      <strong>Privacidad:</strong> no introduzcas datos sensibles ni secretos. La IA no está disponible; el análisis es determinista y no demuestra causas.</aside>
    <section>
      <h2>1. Entrada</h2>
      <p>Formato: <code>timestamp nivel servicio - mensaje</code>. Máximo 1 MiB UTF-8 y 10 000 líneas no vacías.</p>
      <label htmlFor="source">Fuente</label>
      <select id="source" value={source} onChange={e => changeSource(e.target.value)}>
        <option value="texto">Pegar texto</option>
        <option value="archivo">Archivo</option>
        <option value="incidente">Ejemplo de incidente</option>
        <option value="normal">Ejemplo normal</option>
      </select>
      {source === 'archivo' && <>
        <label htmlFor="file">Archivo .log o .txt en UTF-8</label>
        <input id="file" type="file" accept=".log,.txt" onChange={e => void loadFile(e.target.files?.[0])} />
      </>}
      <label htmlFor="input">Contenido {source !== 'texto' && '(solo lectura)'}</label>
      <textarea id="input" rows={8} value={text} readOnly={source !== 'texto'} onChange={e => { reset(); setText(e.target.value); }} spellCheck={false} />
      <button onClick={() => void analyze()} disabled={busy}>Analizar logs</button>
      {busy && <p role="status">Procesando entrada…</p>}
      {error && <p className="error" role="alert">
        {error}</p>}
    </section>
    {analysis && <>
      <section>
        <h2>2. Resumen global</h2>
        <p>Todos los eventos, independientemente de los filtros. El rango describe los logs disponibles, no la duración confirmada del incidente.</p>
        <dl className="summary">
          <div>
            <dt>Procesados</dt>
            <dd>
              {analysis.summary.processedCount}</dd>
          </div>
          <div>
            <dt>Rechazados</dt>
            <dd>
              {analysis.summary.rejectedCount}</dd>
          </div>
          <div>
            <dt>Líneas vacías</dt>
            <dd>
              {analysis.blankLineCount}</dd>
          </div>
          <div>
            <dt>Primer evento UTC</dt>
            <dd>
              {analysis.summary.firstEvent}</dd>
          </div>
          <div>
            <dt>Último evento UTC</dt>
            <dd>
              {analysis.summary.lastEvent}</dd>
          </div>
        </dl>
        <p>
          {levels.map(l => `${l}: ${analysis.summary.levelCounts[l] ?? 0}`).join(' · ')}</p>
        {analysis.warnings.map(w => <p className="warning" key={w}>
          {w}</p>)}
        {analysis.invalidLineNumbers.length > 0 && <p>Líneas rechazadas: {analysis.invalidLineNumbers.join(', ')}</p>}
        <h3>Mensajes repetidos</h3>
        {analysis.groups.length ? <ul>
          {analysis.groups.map(g => <li key={g.normalizedMessage}>
            <code>
              {g.normalizedMessage}</code> — {g.eventIds.length} eventos ({g.eventIds.join(', ')})</li>)}</ul> : <p>No hay mensajes repetidos.</p>}
      </section>
      <section>
        <h2>3. Cronología y evidencia</h2>
        <div className="filters">
          <div>
            <label htmlFor="level">Nivel</label>
            <select id="level" value={level} onChange={e => setLevel(e.target.value)}>
              <option value="">Todos</option>
              {levels.map(l => <option key={l}>
                {l}</option>)}</select>
          </div>
          <div>
            <label htmlFor="search">Buscar texto</label>
            <input id="search" value={search} onChange={e => setSearch(e.target.value)} />
          </div>
          <button className="secondary" onClick={() => { setLevel(''); setSearch(''); }}>Limpiar filtros</button>
        </div>
        <p>
          {filtered.length} de {analysis.events.length} eventos</p>
        <div className="evidence-layout"><div className="timeline">
          {filtered.length ? filtered.map(e => <button className="event" key={e.id} onClick={() => setSelected(e)} aria-pressed={selected?.id === e.id}>
            <span className={'level ' + e.level}>
              {e.level}</span>
            <span>
              <strong>
                {e.service}</strong> · {e.timestampUtc} UTC<br />
              {e.message}</span>
            <code>
              {e.id}</code>
          </button>) : <p>No hay coincidencias.</p>}</div>
        {selected && <div className="detail">
          <h3>Detalle · {selected.id}</h3>
          <p>Línea {selected.lineNumber} · Original: <code>
            {selected.timestampOriginal}</code> · UTC: <code>
              {selected.timestampUtc}</code>
          </p>
          <pre>
            {selected.rawLine}</pre>
        </div>}</div>
      </section>
      <section>
        <h2>4. Borrador y descarga</h2>
        <p>El borrador incluye el análisis completo, no solo los filtros visibles. Revisa los campos pendientes antes de compartir.</p>
        <button onClick={() => setDraft(true)}>Mostrar borrador</button>
        {draft && <>
          <button className="secondary download" onClick={download}>Descargar .md</button>
          <pre className="draft">
            {result?.postmortemMarkdown}</pre>
        </>}</section>
    </>}
    {!analysis && !busy && <section className="empty-results" aria-label="Resultados">
      <h2>Resultados</h2>
      <p>Analiza una entrada para consultar el resumen, la cronología y la evidencia.</p>
    </section>}
  </main>;
}

createRoot(document.getElementById('root')!).render(<React.StrictMode>
  <App />
</React.StrictMode>);
