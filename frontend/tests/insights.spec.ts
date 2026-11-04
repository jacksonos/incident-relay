import { test, expect, type Page } from '@playwright/test';
import { readFile } from 'node:fs/promises';

const aiResult = {
  mode: 'assisted',
  hypotheses: [{ statement: '<script>alert(1)</script> **posible**', evidenceEventIds: ['event-2'], uncertainties: ['Faltan datos'], nextChecks: ['Revisar métricas'] }],
  openQuestions: ['¿Qué pasó?'], warnings: ['Advertencia de prueba'], coverage: { selectedEvents: 11, totalEvents: 11 },
};

async function ready(page: Page) {
  await page.route('**/api/insights/config', route => route.fulfill({ json: { available: true } }));
  await page.goto('/');
  await page.getByLabel('Fuente').selectOption('incidente');
  await page.getByRole('button', { name: 'Analizar logs' }).click();
  await expect(page.getByText('11 de 11 eventos')).toBeVisible();
}

async function download(page: Page) {
  const pending = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Descargar .md' }).click();
  return readFile((await (await pending).path())!, 'utf8');
}

test('consentimiento explícito, selección completa y descarga asistida segura', async ({ page }, testInfo) => {
  let calls = 0;
  let payload: any;
  await page.route('**/api/insights', route => { calls++; payload = route.request().postDataJSON(); return route.fulfill({ json: aiResult }); });
  await ready(page);
  await expect(page.locator('header .eyebrow')).toContainText('MODO DETERMINISTA');
  await page.getByRole('button', { name: 'Mostrar borrador' }).click();
  const original = await page.locator('.draft').textContent();
  expect(await download(page)).toBe(original);
  expect(calls).toBe(0);
  const button = page.getByRole('button', { name: 'Solicitar hipótesis con IA' });
  await expect(button).toBeDisabled();
  await page.getByLabel('Nivel').selectOption('ERROR');
  await page.getByLabel('Autorizo enviar').check();
  expect(calls).toBe(0);
  await button.click();
  await expect(page.getByRole('heading', { name: 'Hipótesis asistidas, pendientes de verificación' })).toBeVisible();
  await expect(page.locator('header .eyebrow')).toContainText('MODO ASISTIDO');
  await expect(page.locator('header .eyebrow')).not.toContainText('MODO DETERMINISTA');
  expect(calls).toBe(1);
  expect(Object.keys(payload).sort()).toEqual(['consent', 'events', 'totalEvents']);
  expect(payload.totalEvents).toBe(11);
  expect(payload.events).toHaveLength(11);
  expect(Object.keys(payload.events[0]).sort()).toEqual(['id', 'level', 'lineNumber', 'message', 'service', 'timestampUtc']);
  expect(payload.events[0].id).toBe('event-2');
  await expect(page.getByText('event-2 (línea 2)')).toBeVisible();
  await expect(page.locator('.ai-result').getByText('Advertencia de prueba')).toBeVisible();
  await expect(page.locator('.ai-result').getByText('¿Qué pasó?')).toBeVisible();
  const preview = await page.locator('.draft').textContent();
  const md = await download(page);
  expect(md).toBe(preview);
  expect(md).toContain('Modo del documento: asistido; sección de hechos determinista.');
  expect(md).toContain(original);
  expect(md).toContain('Cobertura: 11 de 11 eventos.');
  expect(md).not.toContain('<script>');
  expect(md).toContain('&#60;script&#62;');
  expect(md).toContain('&#42;&#42;posible&#42;&#42;');
  await page.screenshot({ path: testInfo.outputPath('ia-asistida.png'), fullPage: true });
});

for (const status of [503, 502, 504]) {
  test(`error IA ${status} conserva descarga original`, async ({ page }) => {
    await page.route('**/api/insights', route => route.fulfill({ status, body: 'SECRET' }));
    await ready(page);
    await page.getByRole('button', { name: 'Mostrar borrador' }).click();
    const original = await page.locator('.draft').textContent();
    await page.getByLabel('Autorizo enviar').check();
    await page.getByRole('button', { name: 'Solicitar hipótesis con IA' }).click();
    await expect(page.getByRole('alert')).toBeVisible();
    await expect(page.locator('body')).not.toContainText('SECRET');
    expect(await download(page)).toBe(original);
  });
}

test('cambio de fuente cancela solicitud y restablece consentimiento', async ({ page }) => {
  let release!: () => void;
  let started!: () => void;
  const gate = new Promise<void>(r => { release = r; });
  const began = new Promise<void>(r => { started = r; });
  await page.route('**/api/insights', async route => { started(); await gate; try { await route.fulfill({ json: aiResult }); } catch { /* Cancelada al cambiar fuente. */ } });
  await ready(page);
  await page.getByLabel('Autorizo enviar').check();
  await page.getByRole('button', { name: 'Solicitar hipótesis con IA' }).click();
  await began;
  const failed = page.waitForEvent('requestfailed', req => req.url().endsWith('/api/insights'));
  await page.getByLabel('Fuente').selectOption('normal');
  release();
  await failed;
  await page.getByRole('button', { name: 'Analizar logs' }).click();
  await expect(page.getByText('3 de 3 eventos')).toBeVisible();
  await expect(page.getByLabel('Autorizo enviar')).not.toBeChecked();
  await expect(page.getByRole('heading', { name: 'Hipótesis asistidas, pendientes de verificación' })).toHaveCount(0);
});

test('primera selección limitada a 100 eventos y exceso de 64 KiB bloqueado', async ({ page }) => {
  await page.route('**/api/insights/config', route => route.fulfill({ json: { available: true } }));
  let calls = 0;
  await page.route('**/api/insights', route => { calls++; return route.fulfill({ json: aiResult }); });
  await page.route('**/api/analyze', async route => {
    const response = await route.fetch();
    const data = await response.json();
    const first = data.analysis.events[0];
    data.analysis.events = Array.from({ length: 101 }, (_, i) => ({ ...first, id: `event-${i + 1}`, lineNumber: i + 1, message: 'ñ'.repeat(400) }));
    await route.fulfill({ json: data });
  });
  await page.goto('/');
  await page.getByLabel('Fuente').selectOption('normal');
  await page.getByRole('button', { name: 'Analizar logs' }).click();
  await expect(page.getByText(/primeros 100 eventos cronológicos de un total de 101/)).toBeVisible();
  await expect(page.getByText(/supera 65 536 bytes/)).toBeVisible();
  await page.getByLabel('Autorizo enviar').check();
  await expect(page.getByRole('button', { name: 'Solicitar hipótesis con IA' })).toBeDisabled();
  expect(calls).toBe(0);
});

test('envía solo los primeros 100 eventos incluso con filtros activos', async ({ page }) => {
  await page.route('**/api/insights/config', route => route.fulfill({ json: { available: true } }));
  let sent: any;
  await page.route('**/api/insights', route => { sent = route.request().postDataJSON(); return route.fulfill({ json: { ...aiResult, coverage: { selectedEvents: 100, totalEvents: 101 } } }); });
  await page.route('**/api/analyze', async route => {
    const data = await (await route.fetch()).json();
    const first = data.analysis.events[0];
    data.analysis.events = Array.from({ length: 101 }, (_, i) => ({ ...first, id: `event-${i + 1}`, lineNumber: i + 1 }));
    await route.fulfill({ json: data });
  });
  await page.goto('/');
  await page.getByLabel('Fuente').selectOption('normal');
  await page.getByRole('button', { name: 'Analizar logs' }).click();
  await expect(page.getByText(/primeros 100 eventos cronológicos de un total de 101/)).toBeVisible();
  await page.getByLabel('Buscar texto').fill('sin coincidencia');
  await page.getByLabel('Autorizo enviar').check();
  await page.getByRole('button', { name: 'Solicitar hipótesis con IA' }).click();
  await expect(page.getByText('Cobertura: 100 de 101 eventos.', { exact: false })).toBeVisible();
  expect(sent.totalEvents).toBe(101);
  expect(sent.events).toHaveLength(100);
  expect(sent.events[99].id).toBe('event-100');
});

for (const state of ['false', 'failure', 'pending'] as const) {
  test(`configuración ${state} impide solicitudes IA`, async ({ page }) => {
    let calls = 0;
    let release!: () => void;
    let started!: () => void;
    let finished!: () => void;
    const gate = new Promise<void>(resolve => { release = resolve; });
    const began = new Promise<void>(resolve => { started = resolve; });
    const done = new Promise<void>(resolve => { finished = resolve; });
    await page.route('**/api/insights/config', async route => {
      started();
      try {
        if (state === 'pending') await gate;
        if (state === 'failure') await route.abort();
        else await route.fulfill({ json: { available: false } });
      } catch { /* Desmontaje o cancelación del navegador. */ }
      finally { finished(); }
    });
    await page.route('**/api/insights', route => { calls++; return route.abort(); });
    try {
      await page.goto('/');
      await began;
      if (state !== 'pending') await done;
      await page.getByLabel('Fuente').selectOption('normal');
      await page.getByRole('button', { name: 'Analizar logs' }).click();
      await expect(page.getByText('3 de 3 eventos')).toBeVisible();
      await page.getByLabel('Autorizo enviar').check();
      await expect(page.getByRole('button', { name: 'Solicitar hipótesis con IA' })).toBeDisabled();
      expect(calls).toBe(0);
    } finally { release(); if (state === 'pending') await done; }
  });
}

for (const excess of [false, true]) {
  test(`selección UTF-8 ${excess ? 'supera' : 'alcanza'} exactamente el límite`, async ({ page }) => {
    await page.route('**/api/insights/config', route => route.fulfill({ json: { available: true } }));
    let sent: any;
    let calls = 0;
    let message = '';
    await page.route('**/api/insights', route => { calls++; sent = route.request().postDataJSON(); return route.fulfill({ json: aiResult }); });
    await page.route('**/api/analyze', async route => {
      const data = await (await route.fetch()).json();
      const event = { ...data.analysis.events[0], message: '' };
      const { id, lineNumber, timestampUtc, level, service } = event;
      const base = Buffer.byteLength(JSON.stringify([{ id, lineNumber, timestampUtc, level, service, message: '' }]));
      const remaining = 65536 - base;
      message = 'ñ'.repeat(Math.floor(remaining / 2)) + 'a'.repeat(remaining % 2) + (excess ? 'ñ' : '');
      event.message = message;
      data.analysis.events = [event];
      expect(Buffer.byteLength(JSON.stringify([{ id, lineNumber, timestampUtc, level, service, message }]))).toBe(excess ? 65538 : 65536);
      await route.fulfill({ json: data });
    });
    await page.goto('/');
    await page.getByLabel('Fuente').selectOption('normal');
    await page.getByRole('button', { name: 'Analizar logs' }).click();
    await expect(page.getByText(/primeros 1 eventos cronológicos/)).toBeVisible();
    await page.getByLabel('Autorizo enviar').check();
    const button = page.getByRole('button', { name: 'Solicitar hipótesis con IA' });
    if (excess) {
      await expect(button).toBeDisabled();
      await expect(page.getByText(/supera 65 536 bytes/)).toBeVisible();
      expect(calls).toBe(0);
    } else {
      await expect(button).toBeEnabled();
      await button.click();
      await expect(page.getByRole('heading', { name: 'Hipótesis asistidas, pendientes de verificación' })).toBeVisible();
      expect(calls).toBe(1);
      expect(sent.events[0].message).toBe(message);
      expect(Buffer.byteLength(JSON.stringify(sent.events))).toBe(65536);
    }
  });
}

test('nuevo análisis cancela IA pendiente y descarta respuesta obsoleta', async ({ page }) => {
  let release!: () => void;
  let started!: () => void;
  let finished!: () => void;
  const gate = new Promise<void>(resolve => { release = resolve; });
  const began = new Promise<void>(resolve => { started = resolve; });
  const done = new Promise<void>(resolve => { finished = resolve; });
  await page.route('**/api/insights', async route => {
    started();
    try { await gate; await route.fulfill({ json: aiResult }); } catch { /* Solicitud cancelada. */ }
    finally { finished(); }
  });
  await ready(page);
  await page.getByLabel('Autorizo enviar').check();
  await page.getByRole('button', { name: 'Solicitar hipótesis con IA' }).click();
  await began;
  const failed = page.waitForEvent('requestfailed', request => request.url().endsWith('/api/insights'));
  try {
    await page.getByRole('button', { name: 'Analizar logs' }).click();
    release();
    await Promise.all([failed, done]);
    await expect(page.getByText('11 de 11 eventos')).toBeVisible();
    await expect(page.getByLabel('Autorizo enviar')).not.toBeChecked();
    await expect(page.getByRole('heading', { name: 'Hipótesis asistidas, pendientes de verificación' })).toHaveCount(0);
    await expect(page.locator('header .eyebrow')).toContainText('MODO DETERMINISTA');
  } finally { release(); }
});
