import React, { useEffect, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import axios from 'axios';
import incident from '../../fixtures/incident.log?raw';
import normal from '../../fixtures/normal.log?raw';
import './style.css';

type Level = 'DEBUG' | 'INFO' | 'WARN' | 'ERROR';
type Event = { id: string; lineNumber: number; timestampOriginal: string; timestampUtc: string; level: Level; service: string; message: string; rawLine: string };
type Result = { analysis: { events: Event[]; invalidLineNumbers: number[]; blankLineCount: number; warnings: string[]; summary: { processedCount: number; rejectedCount: number; firstEvent: string; lastEvent: string; levelCounts: Record<Level, number> }; groups: { normalizedMessage: string; eventIds: string[] }[] }; postmortemMarkdown: string };
type Insights = { mode: 'assisted'; hypotheses: { statement: string; evidenceEventIds: string[]; uncertainties: string[]; nextChecks: string[] }[]; openQuestions: string[]; warnings: string[]; coverage: { selectedEvents: number; totalEvents: number } };
const levels: Level[] = ['DEBUG', 'INFO', 'WARN', 'ERROR'];
const limit = 1024 * 1024;

function safeMarkdown(text: string) {
  return text.replace(/[\r\n]+/g, ' ').replace(/[&<>\\`*_{}\[\]()#+.!|~\-"':/@]/g, char => `&#${char.charCodeAt(0)};`);
}

function assistedMarkdown(result: Result, insights: Insights) {
  const lines = ['Modo del documento: asistido; sección de hechos determinista.', '', result.postmortemMarkdown,
    '', '## Hipótesis asistidas, pendientes de verificación', '',
    `Cobertura: ${insights.coverage.selectedEvents} de ${insights.coverage.totalEvents} eventos.`,
    'La correlación no demuestra causalidad. Las referencias no demuestran apoyo causal.', ''];
  for (const hypothesis of insights.hypotheses) {
    lines.push(`- ${safeMarkdown(hypothesis.statement)}`);
    lines.push(`  - Evidencia: ${hypothesis.evidenceEventIds.map(id => {
      const event = result.analysis.events.find(e => e.id === id);
      return `${safeMarkdown(id)}${event ? ` (línea ${event.lineNumber})` : ''}`;
    }).join(', ')}`);
    for (const value of hypothesis.uncertainties) lines.push(`  - Incertidumbre: ${safeMarkdown(value)}`);
    for (const value of hypothesis.nextChecks) lines.push(`  - Próxima comprobación: ${safeMarkdown(value)}`);
  }
  for (const value of insights.openQuestions) lines.push(`- Pregunta abierta: ${safeMarkdown(value)}`);
  for (const value of insights.warnings) lines.push(`- Advertencia: ${safeMarkdown(value)}`);
  return lines.join('\n') + '\n';
}

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
  const aiRequest = useRef<AbortController | null>(null);
  const [available, setAvailable] = useState(false);
  const [consent, setConsent] = useState(false);
  const [aiBusy, setAiBusy] = useState(false);
  const [aiError, setAiError] = useState('');
  const [insights, setInsights] = useState<Insights | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    axios.get<{ available: boolean }>('/api/insights/config', { signal: controller.signal })
      .then(response => { if (!controller.signal.aborted) setAvailable(response.data.available === true); })
      .catch(() => { if (!controller.signal.aborted) setAvailable(false); });
    return () => { controller.abort(); aiRequest.current?.abort(); };
  }, []);

  function reset() {
    generation.current++;
    request.current?.abort();
    request.current = null;
    aiRequest.current?.abort(); aiRequest.current = null;
    setConsent(false); setAiBusy(false); setAiError(''); setInsights(null);
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
  const exportedMarkdown = result ? (insights ? assistedMarkdown(result, insights) : result.postmortemMarkdown) : '';
  function download() {
    if (!result) return;
    const url = URL.createObjectURL(new Blob([exportedMarkdown], { type: 'text/markdown;charset=utf-8' }));
    const link = document.createElement('a'); link.href = url; link.download = 'postmortem.md'; link.click(); URL.revokeObjectURL(url);
  }
  const analysis = result?.analysis;
  const aiEvents = analysis?.events.slice(0, 100).map(({ id, lineNumber, timestampUtc, level, service, message }) => ({ id, lineNumber, timestampUtc, level, service, message })) ?? [];
  const tooLarge = new TextEncoder().encode(JSON.stringify(aiEvents)).length > 65536;
  async function requestInsights() {
    if (!analysis || !available || !consent || aiBusy || tooLarge) return;
    const current = generation.current;
    const controller = new AbortController(); aiRequest.current = controller;
    setAiBusy(true); setAiError(''); setInsights(null);
    try {
      const response = await axios.post<Insights>('/api/insights', { consent: true, totalEvents: analysis.events.length, events: aiEvents }, { signal: controller.signal, timeout: 20000 });
      if (current === generation.current && !controller.signal.aborted) setInsights(response.data);
    } catch (err) {
      if (current !== generation.current || controller.signal.aborted) return;
      const status = axios.isAxiosError(err) ? err.response?.status : undefined;
      setAiError(status === 400 ? 'La selección de eventos no es válida.' : status === 413 ? 'La selección supera los límites permitidos.' : status === 503 ? 'El análisis asistido no está disponible.' : status === 502 ? 'No se pudo obtener una respuesta válida del proveedor.' : status === 504 ? 'Se agotó el tiempo de espera del proveedor.' : 'No se pudo conectar con el servicio de análisis asistido.');
    } finally { if (current === generation.current && !controller.signal.aborted) { setAiBusy(false); aiRequest.current = null; } }
  }
  const filtered = analysis?.events.filter(e => (!level || e.level === level) && `${e.id} ${e.service} ${e.message} ${e.rawLine}`.toLocaleLowerCase().includes(search.toLocaleLowerCase())) ?? [];
  return <main>
    <header>
      <p className="eyebrow">HERRAMIENTA LOCAL · {insights ? 'MODO ASISTIDO' : 'MODO DETERMINISTA'}</p>
      <h1>Incident Replay</h1>
      <p>Reconstruye una cronología a partir de logs y revisa la evidencia.</p>
    </header>
    <aside className="notice">
      <strong>Privacidad:</strong> no introduzcas datos sensibles ni secretos. El análisis inicial es determinista; la IA requiere configuración del servidor y tu consentimiento explícito. Ninguno demuestra causas.</aside>
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
      <section aria-labelledby="ai-heading">
        <h2 id="ai-heading">Análisis asistido opcional</h2>
        <p>{available ? 'IA disponible en el servidor.' : 'IA no configurada o no disponible en el servidor.'}</p>
        <p>Se seleccionan los primeros {aiEvents.length} eventos cronológicos de un total de {analysis.events.length}, del análisis completo e independientemente de los filtros.</p>
        <p>Se enviarán ID, número de línea, fecha UTC, nivel, servicio y mensaje. El proveedor puede retener estos datos. La correlación no demuestra causalidad.</p>
        {tooLarge && <p className="warning">La selección supera 65 536 bytes UTF-8; no se enviará ni se truncarán mensajes.</p>}
        <label className="consent"><input type="checkbox" checked={consent} onChange={e => setConsent(e.target.checked)} /> Autorizo enviar los eventos seleccionados al proveedor de IA.</label>
        <button onClick={() => void requestInsights()} disabled={!available || !consent || aiBusy || tooLarge}>Solicitar hipótesis con IA</button>
        {aiBusy && <p role="status">Solicitando hipótesis…</p>}
        {aiError && <p className="error" role="alert">{aiError}</p>}
        {insights && <div className="ai-result">
          <h3>Hipótesis asistidas, pendientes de verificación</h3>
          <p>Cobertura: {insights.coverage.selectedEvents} de {insights.coverage.totalEvents} eventos. Las referencias no demuestran causalidad.</p>
          {insights.warnings.map((warning, i) => <p className="warning" key={i}>{warning}</p>)}
          {insights.hypotheses.map((hypothesis, i) => <div key={i}>
            <h3>{hypothesis.statement}</h3>
            <p>Evidencia: {hypothesis.evidenceEventIds.map(id => { const event = analysis.events.find(e => e.id === id); return `${id}${event ? ` (línea ${event.lineNumber})` : ''}`; }).join(', ')}</p>
            {hypothesis.uncertainties.length > 0 && <><strong>Incertidumbres</strong><ul>{hypothesis.uncertainties.map((value, index) => <li key={index}>{value}</li>)}</ul></>}
            {hypothesis.nextChecks.length > 0 && <><strong>Próximas comprobaciones</strong><ul>{hypothesis.nextChecks.map((value, index) => <li key={index}>{value}</li>)}</ul></>}
          </div>)}
          {insights.openQuestions.length > 0 && <><h3>Preguntas abiertas</h3><ul>{insights.openQuestions.map((value, i) => <li key={i}>{value}</li>)}</ul></>}
        </div>}
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
            {exportedMarkdown}</pre>
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
