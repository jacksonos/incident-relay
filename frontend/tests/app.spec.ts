import { test, expect } from '@playwright/test';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';

test('flujo real con fixture, filtros, evidencia y descarga completa', async ({ page, request }, testInfo) => {
  let requests = 0;
  page.on('request', req => { if (req.url().endsWith('/api/analyze')) requests++; });
  await page.goto('/');
  await page.getByLabel('Fuente').selectOption('incidente');
  await expect(page.getByRole('heading', { name: '2. Resumen global' })).toHaveCount(0);
  expect(requests).toBe(0);
  await page.getByRole('button', { name: 'Analizar logs' }).click();
  await expect(page.getByText('Análisis parcial:', { exact: false })).toBeVisible();
  await expect(page.getByText('11 de 11 eventos')).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('desktop.png'), fullPage: true });
  await page.getByLabel('Nivel').selectOption('ERROR');
  await page.getByLabel('Buscar texto').fill('ninguna coincidencia');
  await expect(page.getByText('No hay coincidencias.')).toBeVisible();
  await page.getByRole('button', { name: 'Limpiar filtros' }).click();
  await expect(page.getByText('11 de 11 eventos')).toBeVisible();
  await page.locator('.event').first().click();
  await expect(page.getByRole('heading', { name: /Detalle/ })).toBeVisible();
  await expect(page.locator('.detail pre')).not.toBeEmpty();
  await page.getByLabel('Nivel').selectOption('ERROR');
  await page.getByRole('button', { name: 'Mostrar borrador' }).click();
  const fixture = await readFile(resolve('../fixtures/incident.log'), 'utf8');
  const response = await request.post('http://127.0.0.1:8080/api/analyze', { data: { text: fixture } });
  const expected = (await response.json()).postmortemMarkdown;
  await expect(page.locator('.draft')).toHaveText(expected);
  const downloadPromise = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Descargar .md' }).click();
  const download = await downloadPromise;
  expect(await readFile((await download.path())!, 'utf8')).toBe(expected);
});

test('validación de entrada y archivo UTF-8', async ({ page }) => {
  let requests = 0;
  page.on('request', req => { if (req.url().endsWith('/api/analyze')) requests++; });
  await page.goto('/');
  await page.getByRole('button', { name: 'Analizar logs' }).click();
  await expect(page.getByRole('alert')).toContainText('vacía');
  await page.getByLabel('Fuente').selectOption('archivo');
  const input = page.locator('#file');
  await input.setInputFiles({ name: 'bad.pdf', mimeType: 'application/pdf', buffer: Buffer.from('x') });
  await expect(page.getByRole('alert')).toContainText('.log o .txt');
  await input.setInputFiles({ name: 'bad.log', mimeType: 'text/plain', buffer: Buffer.from([0xff]) });
  await expect(page.getByRole('alert')).toContainText('UTF-8 válido');
  await input.setInputFiles({ name: 'large.log', mimeType: 'text/plain', buffer: Buffer.alloc(1048577, 65) });
  await expect(page.getByRole('alert')).toContainText('1 MiB');
  await input.setInputFiles({ name: 'lines.txt', mimeType: 'text/plain', buffer: Buffer.from('x\n'.repeat(10001)) });
  await expect(page.getByRole('alert')).toContainText('10 000');
  await input.setInputFiles(resolve('../fixtures/normal.log'));
  await expect(page.getByLabel('Contenido (solo lectura)')).not.toBeEmpty();
  await expect(page.getByRole('heading', { name: '2. Resumen global' })).toHaveCount(0);
  expect(requests).toBe(0);
  await page.getByRole('button', { name: 'Analizar logs' }).click();
  await expect(page.getByText('3 de 3 eventos')).toBeVisible();
});

test('errores seguros, carga y respuesta obsoleta', async ({ page }) => {
  await page.goto('/');
  await page.getByLabel('Fuente').selectOption('normal');
  await page.route('**/api/analyze', async route => { await route.fulfill({ status: 400, body: 'SECRET' }); });
  await page.getByRole('button', { name: 'Analizar logs' }).click();
  await expect(page.getByRole('alert')).toContainText('Entrada inválida');
  await expect(page.locator('body')).not.toContainText('SECRET');
  await page.unrouteAll();
  await page.route('**/api/analyze', async route => { await route.fulfill({ status: 413, body: 'SECRET' }); });
  await page.getByRole('button', { name: 'Analizar logs' }).click();
  await expect(page.getByRole('alert')).toContainText('límites');
  await page.unrouteAll();
  await page.route('**/api/analyze', async route => { await route.abort(); });
  await page.getByRole('button', { name: 'Analizar logs' }).click();
  await expect(page.getByRole('alert')).toContainText('conectar');
  await page.unrouteAll();
  let release!: () => void;
  let started!: () => void;
  let finished!: () => void;
  const gate = new Promise<void>(r => { release = r; });
  const handlerStarted = new Promise<void>(r => { started = r; });
  const handlerFinished = new Promise<void>(r => { finished = r; });
  await page.route('**/api/analyze', async route => {
    started();
    await gate;
    try { await route.continue(); } catch { /* El cambio de fuente puede cancelar la petición. */ }
    finally { finished(); }
  });
  await page.getByRole('button', { name: 'Analizar logs' }).click();
  await handlerStarted;
  await expect(page.getByRole('status')).toContainText('Procesando');
  const failed = page.waitForEvent('requestfailed', req => req.url().endsWith('/api/analyze'));
  await page.getByLabel('Fuente').selectOption('texto');
  release();
  await Promise.all([failed, handlerFinished]);
  await expect(page.getByRole('heading', { name: '2. Resumen global' })).toHaveCount(0);
  await expect(page.getByLabel('Contenido')).toHaveValue('');
});

test('móvil y navegación por teclado', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 320, height: 700 });
  await page.goto('/');
  await page.keyboard.press('Tab');
  await expect(page.getByLabel('Fuente')).toBeFocused();
  await page.getByLabel('Fuente').selectOption('normal');
  await page.getByRole('button', { name: 'Analizar logs' }).click();
  await expect(page.getByText('3 de 3 eventos')).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('mobile.png'), fullPage: true });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.setViewportSize({ width: 375, height: 700 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

for (const scheme of ['light', 'dark'] as const) {
  test(`tema ${scheme}, evidencia larga y foco`, async ({ page }, testInfo) => {
    await page.emulateMedia({ colorScheme: scheme });
    await page.setViewportSize({ width: 1280, height: 800 });
    await page.goto('/');
    await expect(page.getByRole('heading', { name: 'Resultados' })).toBeVisible();
    await page.keyboard.press('Tab');
    await expect(page.getByLabel('Fuente')).toBeFocused();
    const contrast = await page.evaluate(() => {
      const rgb = (value: string) => value.match(/[\d.]+/g)!.slice(0, 3).map(Number);
      const luminance = (color: number[]) => color.map(v => v / 255).map(v => v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4).reduce((sum, v, i) => sum + v * [0.2126, 0.7152, 0.0722][i], 0);
      const ratio = (a: string, b: string) => { const x = luminance(rgb(a)); const y = luminance(rgb(b)); return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05); };
      const button = getComputedStyle(document.querySelector('button')!);
      const label = getComputedStyle(document.querySelector('label')!);
      const surface = getComputedStyle(document.querySelector('section')!);
      const input = getComputedStyle(document.querySelector('select')!);
      return {
        background: getComputedStyle(document.documentElement).backgroundColor,
        outlineStyle: input.outlineStyle,
        outlineWidth: parseFloat(input.outlineWidth),
        ratios: [ratio(button.color, button.backgroundColor), ratio(label.color, surface.backgroundColor), ratio(input.outlineColor, surface.backgroundColor)],
      };
    });
    expect(contrast.background).toBe(scheme === 'light' ? 'rgb(242, 245, 248)' : 'rgb(16, 27, 37)');
    expect(contrast.outlineStyle).not.toBe('none');
    expect(contrast.outlineWidth).toBeGreaterThanOrEqual(2);
    expect(contrast.ratios[0]).toBeGreaterThanOrEqual(4.5);
    expect(contrast.ratios[1]).toBeGreaterThanOrEqual(4.5);
    expect(contrast.ratios[2]).toBeGreaterThanOrEqual(3);
    await page.getByRole('button', { name: 'Analizar logs' }).click();
    await expect(page.getByRole('alert')).toContainText('vacía');
    const raw = `2026-01-01T00:00:00Z ERROR service - ${'evidencia'.repeat(120)}`;
    await page.getByLabel('Contenido').fill(`${raw}\nlínea inválida`);
    let release!: () => void;
    let started!: () => void;
    const gate = new Promise<void>(resolve => { release = resolve; });
    const began = new Promise<void>(resolve => { started = resolve; });
    await page.route('**/api/analyze', async route => { started(); await gate; await route.continue(); });
    await page.getByRole('button', { name: 'Analizar logs' }).click();
    await began;
    await expect(page.getByRole('status')).toContainText('Procesando');
    release();
    await expect(page.getByText('Análisis parcial:', { exact: false })).toBeVisible();
    await page.getByLabel('Buscar texto').fill('sin coincidencia');
    await expect(page.getByText('No hay coincidencias.')).toBeVisible();
    await page.getByRole('button', { name: 'Limpiar filtros' }).click();
    await page.locator('.event').first().focus();
    await page.keyboard.press('Enter');
    await expect(page.locator('.event').first()).toHaveAttribute('aria-pressed', 'true');
    await expect(page.locator('.detail pre')).toHaveText(raw);
    await page.getByRole('button', { name: 'Mostrar borrador' }).click();
    await expect(page.locator('.draft')).not.toBeEmpty();
    const downloaded = page.waitForEvent('download');
    await page.getByRole('button', { name: 'Descargar .md' }).click();
    expect(await readFile((await (await downloaded).path())!, 'utf8')).toBe(await page.locator('.draft').textContent());
    await page.screenshot({ path: testInfo.outputPath(`${scheme}-desktop.png`), fullPage: true });
    for (const width of [1280, 768, 375, 320]) {
      await page.setViewportSize({ width, height: 800 });
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    }
  });
}

test('lectura de archivo antigua no sobrescribe la nueva fuente', async ({ page }) => {
  await page.addInitScript(() => {
    const original = File.prototype.arrayBuffer;
    let release!: () => void;
    let started!: () => void;
    let finished!: () => void;
    const gate = new Promise<void>(resolve => { release = resolve; });
    const began = new Promise<void>(resolve => { started = resolve; });
    const done = new Promise<void>(resolve => { finished = resolve; });
    Object.assign(window, { fileGate: { release, began, done } });
    File.prototype.arrayBuffer = async function () {
      if (this.name !== 'lento.log') return original.call(this);
      started();
      await gate;
      try { return await original.call(this); } finally { finished(); }
    };
  });
  await page.goto('/');
  await page.getByLabel('Fuente').selectOption('archivo');
  await page.locator('#file').setInputFiles({ name: 'lento.log', mimeType: 'text/plain', buffer: Buffer.from('antiguo') });
  await page.evaluate(() => (window as any).fileGate.began);
  await page.getByLabel('Fuente').selectOption('normal');
  const normal = await page.getByLabel('Contenido (solo lectura)').inputValue();
  await page.evaluate(async () => { (window as any).fileGate.release(); await (window as any).fileGate.done; });
  await expect(page.getByLabel('Fuente')).toHaveValue('normal');
  await expect(page.getByLabel('Contenido (solo lectura)')).toHaveValue(normal);
  await expect(page.getByRole('status')).toHaveCount(0);
  await expect(page.getByRole('heading', { name: '2. Resumen global' })).toHaveCount(0);
});

const exactMiB = ('é'.repeat(1023) + '\n').repeat(512) + 'é'.repeat(256);
for (const example of [
  { name: 'más de 1 MiB multibyte', text: exactMiB + 'é', bytes: 1048578, lines: 513, error: '1 MiB', requests: 0 },
  { name: 'más de 10 000 líneas', text: 'x\n'.repeat(10001), bytes: 20002, lines: 10001, error: '10 000', requests: 0, bulk: true },
  { name: '1 MiB exacto', text: exactMiB, bytes: 1048576, lines: 513, error: 'Entrada inválida', requests: 1 },
  { name: '10 000 líneas exactas', text: 'x\n'.repeat(10000), bytes: 20000, lines: 10000, error: 'Entrada inválida', requests: 1, bulk: true },
]) {
  test(`límite de texto: ${example.name}`, async ({ page }) => {
    expect(Buffer.byteLength(example.text, 'utf8')).toBe(example.bytes);
    expect(example.text.split(/\r\n|\r|\n/).filter(line => line.trim()).length).toBe(example.lines);
    let requests = 0;
    await page.route('**/api/analyze', async route => {
      requests++;
      expect(route.request().postDataJSON().text).toBe(example.text);
      await route.fulfill({ status: 400, body: '{}' });
    });
    await page.goto('/');
    const input = page.getByLabel('Contenido');
    if (example.bulk) {
      // shortcut: inserción masiva por evento; interacción nativa con 10 000 líneas pendiente de verificar.
      await input.evaluate((el: HTMLTextAreaElement, text) => {
        Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')!.set!.call(el, text);
        el.dispatchEvent(new Event('input', { bubbles: true }));
      }, example.text);
    } else {
      await input.fill(example.text);
    }
    expect(await input.inputValue()).toBe(example.text);
    await page.getByRole('button', { name: 'Analizar logs' }).click();
    await expect(page.getByRole('alert')).toContainText(example.error);
    expect(requests).toBe(example.requests);
  });
}
