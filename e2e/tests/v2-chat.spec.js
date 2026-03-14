// @ts-check
const { test, expect } = require('@playwright/test');

/**
 * Helper: build an SSE response body from an array of ChatEvent objects.
 */
function sseBody(events) {
  return events.map(e => 'data: ' + JSON.stringify(e) + '\n\n').join('');
}

/**
 * E2E tests for quarkus-coder-agent v1.0.0
 *
 * These tests mock server responses via Playwright route interception
 * so they do not depend on the Claude CLI.
 */
test.describe('v2 Chat - Send and Queue', () => {

  test.beforeEach(async ({ page }) => {
    // Mock /api/status to return not-busy
    await page.route('**/api/status', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ type: 'status', model: 'sonnet', sessionId: null, busy: false }),
      });
    });

    await page.goto('/');
    await page.waitForSelector('#send-btn');
    // Wait for initial status to be loaded
    await page.waitForFunction(() => {
      const el = document.getElementById('connection-status');
      return el && el.textContent === 'ready';
    });
  });

  // ────────────────────────────────────────────
  // Test 1: Basic send (not busy) - message appears in chat
  // ────────────────────────────────────────────
  test('Send when not busy shows user message and receives response', async ({ page }) => {
    // Mock /api/chat to return a valid SSE stream
    await page.route('**/api/chat', async (route) => {
      const body = sseBody([
        { type: 'status', model: 'sonnet', sessionId: 'sess-test', busy: true },
        { type: 'delta', content: 'Hello ' },
        { type: 'delta', content: 'world!' },
        { type: 'result', sessionId: 'sess-test', costUsd: 0.001, durationMs: 500 },
        { type: 'status', model: 'sonnet', sessionId: 'sess-test', busy: false },
      ]);
      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: body,
      });
    });

    // Type and send
    await page.fill('#prompt-input', 'Hello test');
    await page.click('#send-btn');

    // User message should appear in chat
    const chatArea = page.locator('#chat-area');
    await expect(chatArea.locator('.message.user')).toContainText('Hello test');

    // Assistant message should appear
    await expect(chatArea.locator('.message.assistant')).toContainText('Hello world!', { timeout: 5000 });

    // Textarea should be cleared
    await expect(page.locator('#prompt-input')).toHaveValue('');
  });

  // ────────────────────────────────────────────
  // Test 2: Send adds to queue and processes immediately when not busy
  // ────────────────────────────────────────────
  test('Send goes through queue and processes immediately', async ({ page }) => {
    let chatRequests = [];

    await page.route('**/api/chat', async (route) => {
      const req = route.request();
      const postData = JSON.parse(req.postData());
      chatRequests.push(postData.text);

      const body = sseBody([
        { type: 'status', model: 'sonnet', sessionId: 'sess-test', busy: true },
        { type: 'delta', content: 'Response to: ' + postData.text },
        { type: 'result', sessionId: 'sess-test', costUsd: 0.001, durationMs: 100 },
        { type: 'status', model: 'sonnet', sessionId: 'sess-test', busy: false },
      ]);
      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: body,
      });
    });

    // Send a message
    await page.fill('#prompt-input', 'Queue test message');
    await page.click('#send-btn');

    // Should appear in chat
    await expect(page.locator('#chat-area .message.user')).toContainText('Queue test message');

    // Server should have received the request
    await page.waitForTimeout(500);
    expect(chatRequests).toContain('Queue test message');
  });

  // ────────────────────────────────────────────
  // Test 3: Send while busy adds to queue, auto-sends after
  // ────────────────────────────────────────────
  test('Send while busy queues message and auto-sends after completion', async ({ page }) => {
    let resolveFirst;
    let chatRequests = [];

    await page.route('**/api/chat', async (route) => {
      const req = route.request();
      const postData = JSON.parse(req.postData());
      chatRequests.push(postData.text);

      if (postData.text === 'First message') {
        // First request: delay response to simulate busy
        await new Promise(resolve => { resolveFirst = resolve; });
      }

      const body = sseBody([
        { type: 'status', model: 'sonnet', sessionId: 'sess-test', busy: true },
        { type: 'delta', content: 'Reply to: ' + postData.text },
        { type: 'result', sessionId: 'sess-test', costUsd: 0.001, durationMs: 100 },
        { type: 'status', model: 'sonnet', sessionId: 'sess-test', busy: false },
      ]);
      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: body,
      });
    });

    // Send first message (will hang)
    await page.fill('#prompt-input', 'First message');
    await page.click('#send-btn');

    // Wait for it to become busy
    await page.waitForTimeout(200);

    // Send second message while busy - should go to queue
    await page.fill('#prompt-input', 'Second message');
    await page.click('#send-btn');
    await page.waitForTimeout(100);

    // First user message should be in chat
    await expect(page.locator('#chat-area .message.user').first()).toContainText('First message');

    // Second should NOT be in chat yet (still in queue)
    const userMsgs = page.locator('#chat-area .message.user');
    await expect(userMsgs).toHaveCount(1);

    // Now complete the first request
    if (resolveFirst) resolveFirst();

    // Wait for queue processing
    await page.waitForTimeout(1000);

    // Now second message should be in chat too
    await expect(page.locator('#chat-area .message.user')).toHaveCount(2);
    await expect(page.locator('#chat-area .message.user').nth(1)).toContainText('Second message');
  });

  // ────────────────────────────────────────────
  // Test 4: Queue button with text adds with auto=false
  // ────────────────────────────────────────────
  test('Queue button with text adds item with auto=false', async ({ page }) => {
    await page.fill('#prompt-input', 'Queued item');
    await page.click('#queue-btn');

    // Queue area should be visible
    const queueArea = page.locator('#queue-area');
    await expect(queueArea).toHaveCSS('display', 'block');

    // Item should be present
    await expect(queueArea.locator('.queue-item')).toHaveCount(1);
    await expect(queueArea.locator('.queue-text')).toContainText('Queued item');

    // Auto should be UNCHECKED (Queue button adds auto=false)
    const autoCheckbox = queueArea.locator('input[data-queue-auto]').first();
    await expect(autoCheckbox).not.toBeChecked();

    // Textarea should be cleared
    await expect(page.locator('#prompt-input')).toHaveValue('');
  });

  // ────────────────────────────────────────────
  // Test 5: Queue button with empty text toggles visibility
  // ────────────────────────────────────────────
  test('Queue button with empty text toggles queue visibility', async ({ page }) => {
    const queueArea = page.locator('#queue-area');

    // Initially hidden
    await expect(queueArea).toHaveCSS('display', 'none');

    // Click Queue with empty textarea -> show
    await page.click('#queue-btn');
    await expect(queueArea).toHaveCSS('display', 'block');

    // Click again -> hide
    await page.click('#queue-btn');
    await expect(queueArea).toHaveCSS('display', 'none');
  });

  // ────────────────────────────────────────────
  // Test 6: Send with empty text and waiting queue item sends it
  // ────────────────────────────────────────────
  test('Send with empty text sends waiting queue item (auto=false)', async ({ page }) => {
    let chatRequests = [];

    await page.route('**/api/chat', async (route) => {
      const req = route.request();
      const postData = JSON.parse(req.postData());
      chatRequests.push(postData.text);

      const body = sseBody([
        { type: 'status', model: 'sonnet', sessionId: 'sess-test', busy: true },
        { type: 'delta', content: 'OK' },
        { type: 'result', sessionId: 'sess-test', costUsd: 0.001, durationMs: 100 },
        { type: 'status', model: 'sonnet', sessionId: 'sess-test', busy: false },
      ]);
      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: body,
      });
    });

    // Add to queue via Queue button (auto=false)
    await page.fill('#prompt-input', 'Manual send item');
    await page.click('#queue-btn');

    // Item is in queue with auto=false, not sent yet
    await page.waitForTimeout(200);
    expect(chatRequests.length).toBe(0);

    // Press Send with empty textarea -> should send the waiting item
    await page.click('#send-btn');

    // Should appear in chat
    await expect(page.locator('#chat-area .message.user')).toContainText('Manual send item', { timeout: 5000 });
    await page.waitForTimeout(500);
    expect(chatRequests).toContain('Manual send item');
  });

  // ────────────────────────────────────────────
  // Test 7: Queue reordering (up/down arrows)
  // ────────────────────────────────────────────
  test('Queue items can be reordered with up/down buttons', async ({ page }) => {
    // Add 3 items via Queue button
    await page.fill('#prompt-input', 'Item A');
    await page.click('#queue-btn');
    await page.fill('#prompt-input', 'Item B');
    await page.click('#queue-btn');
    await page.fill('#prompt-input', 'Item C');
    await page.click('#queue-btn');

    const queueArea = page.locator('#queue-area');

    // Check initial order: A, B, C
    const texts = queueArea.locator('.queue-text');
    await expect(texts.nth(0)).toContainText('Item A');
    await expect(texts.nth(1)).toContainText('Item B');
    await expect(texts.nth(2)).toContainText('Item C');

    // Move B down -> A, C, B
    await queueArea.locator('[data-queue-down="1"]').click();

    const textsAfter = queueArea.locator('.queue-text');
    await expect(textsAfter.nth(0)).toContainText('Item A');
    await expect(textsAfter.nth(1)).toContainText('Item C');
    await expect(textsAfter.nth(2)).toContainText('Item B');
  });

  // ────────────────────────────────────────────
  // Test 8: Save Chat button exists and works
  // ────────────────────────────────────────────
  test('Save Chat button exists in header', async ({ page }) => {
    const saveBtn = page.locator('#save-chat-btn');
    await expect(saveBtn).toBeVisible();
    await expect(saveBtn).toHaveText('Save Chat');
  });

});

test.describe('v2 Chat - Prompt (interactive)', () => {

  test.beforeEach(async ({ page }) => {
    await page.route('**/api/status', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ type: 'status', model: 'sonnet', sessionId: null, busy: false }),
      });
    });

    await page.goto('/');
    await page.waitForSelector('#send-btn');
    await page.waitForFunction(() => {
      const el = document.getElementById('connection-status');
      return el && el.textContent === 'ready';
    });
  });

  // ────────────────────────────────────────────
  // Test 9: Prompt event displays buttons
  // ────────────────────────────────────────────
  test('Prompt event displays with option buttons', async ({ page }) => {
    // Mock /api/chat: respond with delta then a prompt event
    await page.route('**/api/chat', async (route) => {
      const body = sseBody([
        { type: 'status', model: 'sonnet', sessionId: 'sess-test', busy: true },
        { type: 'delta', content: 'I need permission.' },
        { type: 'prompt', content: 'Allow tool: Bash(ls -la)?', promptId: 'prompt-001', promptType: 'permission', options: ['Allow', 'Deny'] },
      ]);
      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: body,
      });
    });

    await page.fill('#prompt-input', 'Do something');
    await page.click('#send-btn');

    // Prompt message should appear
    const promptMsg = page.locator('.message.prompt');
    await expect(promptMsg).toBeVisible({ timeout: 5000 });
    await expect(promptMsg).toContainText('Allow tool: Bash(ls -la)?');

    // Buttons should be present
    const buttons = promptMsg.locator('.prompt-option-btn');
    await expect(buttons).toHaveCount(2);
    await expect(buttons.nth(0)).toHaveText('Allow');
    await expect(buttons.nth(1)).toHaveText('Deny');
  });

  // ────────────────────────────────────────────
  // Test 10: Clicking prompt button sends response
  // ────────────────────────────────────────────
  test('Clicking prompt button sends response to /api/respond', async ({ page }) => {
    let respondRequest = null;

    // Mock /api/chat: respond with a prompt event
    await page.route('**/api/chat', async (route) => {
      const body = sseBody([
        { type: 'status', model: 'sonnet', sessionId: 'sess-test', busy: true },
        { type: 'prompt', content: 'Continue?', promptId: 'prompt-002', promptType: 'yesno', options: ['Yes', 'No'] },
      ]);
      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: body,
      });
    });

    // Mock /api/respond
    await page.route('**/api/respond', async (route) => {
      const req = route.request();
      respondRequest = JSON.parse(req.postData());
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ type: 'info', content: 'Response sent' }),
      });
    });

    await page.fill('#prompt-input', 'Test prompt');
    await page.click('#send-btn');

    // Wait for prompt to appear
    const promptMsg = page.locator('.message.prompt');
    await expect(promptMsg).toBeVisible({ timeout: 5000 });

    // Click "Yes"
    await promptMsg.locator('.prompt-option-btn', { hasText: 'Yes' }).click();

    // Verify /api/respond was called correctly
    await page.waitForTimeout(500);
    expect(respondRequest).not.toBeNull();
    expect(respondRequest.promptId).toBe('prompt-002');
    expect(respondRequest.response).toBe('Yes');

    // Buttons should be disabled after clicking
    const buttons = promptMsg.locator('.prompt-option-btn');
    for (let i = 0; i < await buttons.count(); i++) {
      await expect(buttons.nth(i)).toBeDisabled();
    }

    // Prompt should have "answered" class
    await expect(promptMsg).toHaveClass(/answered/);
  });

  // ────────────────────────────────────────────
  // Test 11: Server error during chat is displayed
  // ────────────────────────────────────────────
  test('Server error during chat is displayed to user', async ({ page }) => {
    await page.route('**/api/chat', async (route) => {
      const body = sseBody([
        { type: 'error', content: 'Already processing a prompt. Please wait or cancel.' },
        { type: 'status', model: 'sonnet', sessionId: null, busy: false },
      ]);
      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: body,
      });
    });

    await page.fill('#prompt-input', 'Error test');
    await page.click('#send-btn');

    // Error should appear in chat
    const errorMsg = page.locator('.message.error');
    await expect(errorMsg).toContainText('Already processing a prompt', { timeout: 5000 });
  });

  // ────────────────────────────────────────────
  // Test 12: Cancel button works
  // ────────────────────────────────────────────
  test('Cancel button aborts request', async ({ page }) => {
    let cancelCalled = false;

    // Mock /api/chat: never respond (simulate long-running)
    await page.route('**/api/chat', async (route) => {
      // Do not fulfill - let it hang
    });

    await page.route('**/api/cancel', async (route) => {
      cancelCalled = true;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ type: 'info', content: 'Cancelled' }),
      });
    });

    // Send a message (will hang)
    await page.fill('#prompt-input', 'Long running');
    await page.click('#send-btn');

    // Wait for busy state
    await page.waitForTimeout(300);

    // Cancel button should be enabled
    await expect(page.locator('#cancel-btn')).toBeEnabled();

    // Click cancel
    await page.click('#cancel-btn');

    await page.waitForTimeout(500);
    expect(cancelCalled).toBe(true);
  });

  // ────────────────────────────────────────────
  // Test 13: Slash commands bypass queue
  // ────────────────────────────────────────────
  test('Slash commands are sent immediately, not queued', async ({ page }) => {
    let commandRequest = null;

    await page.route('**/api/command', async (route) => {
      const req = route.request();
      commandRequest = JSON.parse(req.postData());
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([
          { type: 'info', content: 'Model changed to: opus' },
          { type: 'status', model: 'opus', sessionId: null, busy: false },
        ]),
      });
    });

    await page.fill('#prompt-input', '/model opus');
    await page.click('#send-btn');

    await page.waitForTimeout(500);
    expect(commandRequest).not.toBeNull();
    expect(commandRequest.text).toBe('/model opus');

    // Info message should appear
    await expect(page.locator('.message.info')).toContainText('Model changed to: opus');
  });

  // ────────────────────────────────────────────
  // Test 14: Multiple sequential sends through queue
  // ────────────────────────────────────────────
  test('Multiple messages sent sequentially through queue', async ({ page }) => {
    let chatRequests = [];

    await page.route('**/api/chat', async (route) => {
      const req = route.request();
      const postData = JSON.parse(req.postData());
      chatRequests.push(postData.text);

      const body = sseBody([
        { type: 'status', model: 'sonnet', sessionId: 'sess-test', busy: true },
        { type: 'delta', content: 'Reply to ' + postData.text },
        { type: 'result', sessionId: 'sess-test', costUsd: 0.001, durationMs: 100 },
        { type: 'status', model: 'sonnet', sessionId: 'sess-test', busy: false },
      ]);
      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: body,
      });
    });

    // Send message 1
    await page.fill('#prompt-input', 'Message 1');
    await page.click('#send-btn');

    // Wait for completion
    await expect(page.locator('#chat-area .message.assistant')).toHaveCount(1, { timeout: 5000 });

    // Send message 2
    await page.fill('#prompt-input', 'Message 2');
    await page.click('#send-btn');

    // Wait for second completion
    await expect(page.locator('#chat-area .message.assistant')).toHaveCount(2, { timeout: 5000 });

    // Both should have been sent to server
    expect(chatRequests).toEqual(['Message 1', 'Message 2']);

    // Both user messages in chat
    const userMsgs = page.locator('#chat-area .message.user');
    await expect(userMsgs).toHaveCount(2);
    await expect(userMsgs.nth(0)).toContainText('Message 1');
    await expect(userMsgs.nth(1)).toContainText('Message 2');
  });

  // ────────────────────────────────────────────
  // Test 15: Exact reproduction of bug report - model change then 3 messages
  // User reported: after hello got response, next messages stuck in queue
  // ────────────────────────────────────────────
  test('Model change then sequential conversation does not get stuck in queue', async ({ page }) => {
    let chatRequests = [];

    // Mock /api/command for model change
    await page.route('**/api/command', async (route) => {
      const req = route.request();
      const postData = JSON.parse(req.postData());
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([
          { type: 'info', content: 'Model changed to: opus' },
          { type: 'status', model: 'opus', sessionId: null, busy: false },
        ]),
      });
    });

    // Mock /api/chat
    await page.route('**/api/chat', async (route) => {
      const req = route.request();
      const postData = JSON.parse(req.postData());
      chatRequests.push(postData.text);

      const body = sseBody([
        { type: 'status', model: 'opus', sessionId: 'sess-abc', busy: true },
        { type: 'delta', content: 'Reply to: ' + postData.text },
        { type: 'result', sessionId: 'sess-abc', costUsd: 0.01, durationMs: 1000 },
        { type: 'status', model: 'opus', sessionId: 'sess-abc', busy: false },
      ]);
      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: body,
      });
    });

    // Step 1: Change model to opus
    await page.selectOption('#model-select', 'opus');
    await expect(page.locator('.message.info')).toContainText('Model changed to: opus');

    // Step 2: Send "hello"
    await page.fill('#prompt-input', 'hello');
    await page.click('#send-btn');

    // Should get a response
    await expect(page.locator('#chat-area .message.assistant')).toHaveCount(1, { timeout: 5000 });
    await expect(page.locator('#chat-area .message.assistant').first()).toContainText('Reply to: hello');

    // Step 3: Send second message - must NOT be stuck in queue
    await page.fill('#prompt-input', 'Would you wait for 1 minute and ask me Yes or No?');
    await page.click('#send-btn');

    // Should get a response (not stuck in queue)
    await expect(page.locator('#chat-area .message.assistant')).toHaveCount(2, { timeout: 5000 });
    await expect(page.locator('#chat-area .message.user').nth(1))
      .toContainText('Would you wait for 1 minute');

    // Step 4: Send third message
    await page.fill('#prompt-input', 'the first my prompt would you...?');
    await page.click('#send-btn');

    // Should get a response
    await expect(page.locator('#chat-area .message.assistant')).toHaveCount(3, { timeout: 5000 });

    // All 3 messages should have been sent to server
    expect(chatRequests).toEqual([
      'hello',
      'Would you wait for 1 minute and ask me Yes or No?',
      'the first my prompt would you...?',
    ]);

    // Queue should show all 3 as sent (if queue area is visible)
    // The queue items with [x] markers should have advanced
    const queueArea = page.locator('#queue-area');
    // Queue area might be hidden after all items processed - that's fine
  });

});
