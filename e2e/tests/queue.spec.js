// @ts-check
const { test, expect } = require('@playwright/test');

// Helper: add an item to the queue via the Queue button (auto=false)
async function addToQueue(page, text) {
  await page.fill('#prompt-input', text);
  await page.click('#queue-btn');
}

test.describe('Queue functionality', () => {

  test.beforeEach(async ({ page }) => {
    // Mock /api/status
    await page.route('**/api/status', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ type: 'status', model: 'sonnet', sessionId: null, busy: false }),
      });
    });

    await page.goto('/');
    await page.waitForSelector('#send-btn');
  });

  // ── Test 1: Page load basics ──────────────────────────────────
  test('page loads with expected elements', async ({ page }) => {
    await expect(page).toHaveTitle(/.+/);
    await expect(page.locator('#send-btn')).toBeVisible();
    await expect(page.locator('#cancel-btn')).toBeVisible();
    await expect(page.locator('#queue-btn')).toBeVisible();
    await expect(page.locator('#prompt-input')).toBeVisible();

    // Queue area should be hidden initially
    const queueArea = page.locator('#queue-area');
    await expect(queueArea).toHaveCSS('display', 'none');
  });

  // ── Test 2: Queue button adds item with auto=false ────────────
  test('Queue button adds item and shows queue area', async ({ page }) => {
    const queueArea = page.locator('#queue-area');
    await expect(queueArea).toHaveCSS('display', 'none');

    await addToQueue(page, 'Hello queue item');

    await expect(queueArea).toHaveCSS('display', 'block');

    const items = queueArea.locator('.queue-item');
    await expect(items).toHaveCount(1);

    const itemText = items.first().locator('.queue-text');
    await expect(itemText).toContainText('Hello queue item');

    // Queue button adds with auto=false (unchecked)
    const autoCheckbox = items.first().locator('input[data-queue-auto]');
    await expect(autoCheckbox).not.toBeChecked();

    const removeBtn = items.first().locator('.queue-remove');
    await expect(removeBtn).toBeVisible();

    await expect(page.locator('#prompt-input')).toHaveValue('');
  });

  // ── Test 3: Multiple items ─────────────────────────────────────
  test('multiple items show correct header count and numbering', async ({ page }) => {
    const queueArea = page.locator('#queue-area');

    await addToQueue(page, 'First item');
    await addToQueue(page, 'Second item');
    await addToQueue(page, 'Third item');

    const header = queueArea.locator('.queue-header');
    await expect(header).toContainText('Queue (3)');

    const items = queueArea.locator('.queue-item');
    await expect(items).toHaveCount(3);

    const indices = queueArea.locator('.queue-index');
    await expect(indices.nth(0)).toHaveText('1.');
    await expect(indices.nth(1)).toHaveText('2.');
    await expect(indices.nth(2)).toHaveText('3.');
  });

  // ── Test 4: Remove button deletes items ─────────────────────────
  test('remove button deletes items', async ({ page }) => {
    const queueArea = page.locator('#queue-area');

    await addToQueue(page, 'Item A');
    await addToQueue(page, 'Item B');
    await expect(queueArea.locator('.queue-item')).toHaveCount(2);

    // Remove first item
    await queueArea.locator('.queue-remove').first().click();
    await expect(queueArea.locator('.queue-item')).toHaveCount(1);
    await expect(queueArea.locator('.queue-text').first()).toContainText('Item B');

    // Remove last item - queue shows "empty" message
    await queueArea.locator('.queue-remove').first().click();
    await expect(queueArea).toContainText('Queue is empty');
  });

  // ── Test 5: Auto checkbox toggle ──────────────────────────────
  test('Auto checkbox can be toggled', async ({ page }) => {
    const queueArea = page.locator('#queue-area');

    await addToQueue(page, 'Auto toggle test');

    // Queue button adds with auto=false (unchecked by default)
    const autoCheckbox = queueArea.locator('input[data-queue-auto]').first();
    await expect(autoCheckbox).not.toBeChecked();

    // Check it
    await autoCheckbox.check();
    await expect(autoCheckbox).toBeChecked();

    // Uncheck it again
    await autoCheckbox.uncheck();
    await expect(autoCheckbox).not.toBeChecked();
  });

  // ── Test 6: Save button exists ────────────────────────────────
  test('Save button appears when queue has items', async ({ page }) => {
    const queueArea = page.locator('#queue-area');
    await expect(queueArea).toHaveCSS('display', 'none');

    await addToQueue(page, 'Save test item');

    const saveBtn = queueArea.locator('#queue-save-btn');
    await expect(saveBtn).toBeVisible();
    await expect(saveBtn).toHaveText('Save');
  });

  // ── Test 7: Queue toggle ──────────────────────────────────────
  test('Queue button with empty text toggles visibility', async ({ page }) => {
    const queueArea = page.locator('#queue-area');
    await expect(queueArea).toHaveCSS('display', 'none');

    // Toggle open
    await page.click('#queue-btn');
    await expect(queueArea).toHaveCSS('display', 'block');

    // Toggle close
    await page.click('#queue-btn');
    await expect(queueArea).toHaveCSS('display', 'none');
  });

  // ── Test 8: Send while busy shows queue area ───────────────────
  test('Send while busy shows queued item in queue area', async ({ page }) => {
    const queueArea = page.locator('#queue-area');

    // Mock /api/chat: never respond (simulate busy)
    await page.route('**/api/chat', async (route) => {
      // Hang forever
    });

    // Send first message (will hang)
    await page.fill('#prompt-input', 'First message');
    await page.locator('#prompt-input').press('Enter');
    await page.waitForTimeout(300);

    // Send second message while busy
    await page.fill('#prompt-input', 'Queued while busy');
    await page.locator('#prompt-input').press('Enter');

    // Queue area should become visible
    await expect(queueArea).toHaveCSS('display', 'block');

    // Queued item should be visible (first item is "First message" being processed, second is the new one)
    const texts = queueArea.locator('.queue-text');
    await expect(texts.last()).toContainText('Queued while busy');
  });

  // ── Test 9: Queue pauses during ask_user prompt ──────────────────
  test('queue pauses while ask_user prompt is displayed and resumes after answer', async ({ page }) => {
    let chatRequests = [];

    // Mock /api/chat: return SSE with ask_user prompt, then result
    await page.route('**/api/chat', async (route) => {
      const body = JSON.parse(route.request().postData());
      chatRequests.push(body.text);

      const promptEvent = JSON.stringify({
        type: 'prompt',
        promptType: 'ask_user',
        content: 'Do you want to continue?',
        options: ['Yes', 'No'],
      });
      const resultEvent = JSON.stringify({
        type: 'result',
        costUsd: 0.001,
        durationMs: 100,
        sessionId: 'test-session-123',
      });

      const sseBody = `data: ${promptEvent}\n\ndata: ${resultEvent}\n\n`;
      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: sseBody,
      });
    });

    // Add a queued item first (auto=true so it would auto-send)
    await page.fill('#prompt-input', 'Queued follow-up');
    await page.locator('#prompt-input').press('Enter');
    // That sends immediately since not busy. Wait for SSE to complete.
    await page.waitForTimeout(500);

    // Now add another item to the queue
    await page.fill('#prompt-input', 'Second follow-up');
    await page.locator('#prompt-input').press('Enter');
    await page.waitForTimeout(300);

    // Prompt should be visible
    const promptDiv = page.locator('.message.prompt');
    await expect(promptDiv.first()).toBeVisible();
    await expect(promptDiv.first()).toContainText('Do you want to continue?');

    // The prompt buttons should be visible and enabled
    const yesBtn = promptDiv.first().locator('.prompt-option-btn', { hasText: 'Yes' });
    await expect(yesBtn).toBeVisible();
    await expect(yesBtn).toBeEnabled();

    // Record how many chat requests were made before answering
    const requestsBefore = chatRequests.length;

    // Wait a bit to confirm queue does NOT auto-send
    await page.waitForTimeout(500);
    expect(chatRequests.length).toBe(requestsBefore);

    // Now answer the prompt
    await yesBtn.click();

    // After answering, the queued item should be sent (processQueue resumes)
    await page.waitForTimeout(500);
    expect(chatRequests.length).toBeGreaterThan(requestsBefore);
  });

});
