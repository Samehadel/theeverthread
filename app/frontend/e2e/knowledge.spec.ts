import { expect, test } from '@playwright/test';

const catalogLabels = {
  'type:coding': 'Coding',
  'area:backend': 'Backend',
  'area:testing': 'Testing',
  'technology:java': 'Java',
};

for (const viewport of [
  { width: 1440, height: 1000 },
  { width: 390, height: 844 },
]) {
  test.describe(`Knowledge capture at ${viewport.width}px`, () => {
    test.use({ viewport });
    test('edits one document with keyboard structure, links and recoverable deletion', async ({
      page,
    }) => {
      await page.route('**/api/**', (route) =>
        route.fulfill({
          json: {
            data: {
              id: 'author',
              fullName: 'Ada',
              workspaceId: 'workspace',
              workspaceName: 'Acme',
              accessRole: 'MEMBER',
              workspaces: [{ id: 'workspace', name: 'Acme', role: 'MEMBER' }],
            },
          },
        }),
      );
      await page.goto('/knowledge/new');
      const editor = page.getByRole('textbox', { name: 'Details Required' });
      await editor.click();
      await page.keyboard.type('## A useful discovery');
      // Markdown input rules activate when a space follows the marker.
      await expect(editor.locator('h2')).toHaveText('A useful discovery');
      await editor.press('Enter');
      await page.keyboard.type('What we learned from the experiment.');
      await editor.press('Enter');
      await page.keyboard.type('- Keep a stable key');
      await editor.press('Enter');
      await page.keyboard.type('Retry safely');
      await expect(editor.locator('li')).toHaveCount(2);
      await editor.press('Enter');
      await editor.press('Enter');
      await page.keyboard.type('Read the reference');
      await page.getByRole('button', { name: '↗ Link', exact: true }).click();
      await page.getByLabel('Link URL').fill('javascript:alert(1)');
      await page.getByRole('button', { name: 'Apply link' }).click();
      await expect(page.getByRole('alert')).toContainText('valid https://');
      await page.getByLabel('Link URL').fill('https://example.com/reference');
      await page.getByRole('button', { name: 'Apply link' }).click();
      await expect(editor.locator('a')).toHaveAttribute(
        'href',
        'https://example.com/reference',
      );
      await editor.click();
      await editor.press('ControlOrMeta+a');
      await editor.press('Backspace');
      await expect(editor).toHaveText('');
      await page.getByRole('button', { name: 'Undo', exact: true }).click();
      await expect(editor.locator('h2')).toHaveText('A useful discovery');
      await expect(editor.locator('li')).toHaveCount(2);
      await expect(editor.locator('a')).toHaveText('Read the reference');
      await page.getByRole('button', { name: 'Redo', exact: true }).click();
      await expect(editor).toHaveText('');
      const undoModifier = await page.evaluate(() =>
        /Mac/.test(navigator.platform) ? 'Meta' : 'Control',
      );
      await editor.press(`${undoModifier}+z`);
      await expect(editor.locator('a')).toHaveText('Read the reference');
      await expect(page.getByRole('tab')).toHaveCount(0);
      expect(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
      ).toBe(true);
    });
    test('validates Coding, refines wording, preserves errors and publishes to the ticket', async ({
      page,
    }) => {
      let refineFails = true;
      let publishFails = true;
      let published: any;
      const ids: string[] = [];
      const refinements: { field: string; mode: string }[] = [];
      await page.route('**/api/**', async (route) => {
        const path = new URL(route.request().url()).pathname;
        if (path === '/api/auth/me' || path.startsWith('/api/workspaces/'))
          return route.fulfill({
            json: {
              data: {
                id: 'author',
                fullName: 'Ada',
                accessRole: 'MEMBER',
                workspaceName: 'Acme',
                workspaceId: 'workspace',
                workspaces: [{ id: 'workspace', name: 'Acme', role: 'MEMBER' }],
              },
            },
          });
        if (path === '/api/auth/csrf')
          return route.fulfill({
            json: { data: { token: 'csrf', headerName: 'X-CSRF-TOKEN' } },
          });
        if (path.endsWith('/refine')) {
          const { field, mode } = route.request().postDataJSON();
          refinements.push({ field, mode });
          expect(['proofread', 'rephrase', 'rewrite']).toContain(mode);
          expect(
            route.request().postDataJSON().content.details[1].text,
          ).toContain('return key;');
          if (refineFails)
            return route.fulfill({
              status: 503,
              json: {
                error: {
                  message: 'Refinement unavailable. Your writing is safe.',
                },
              },
            });
          expect(['title', 'capture']).toContain(field);
          return route.fulfill({
            json: {
              data: {
                field,
                text: field === 'title' ? 'Retry safely' : 'One stable key.',
              },
            },
          });
        }
        if (path === '/api/knowledge/tickets/attributes')
          return route.fulfill({ json: { data: catalogLabels } });
        if (path === '/api/knowledge/tickets') {
          const request = route.request().postDataJSON();
          if (request.content.template === 'Coding') {
            expect(
              request.content.details.find(
                (block: any) => block.type === 'code',
              )?.language,
            ).toBe('python');
          }
          ids.push(request.submissionId);
          published = {
            id: ids.length > 2 ? 'ticket-2' : 'ticket-1',
            content: request.content,
            authorId: 'author',
            authorName: 'Ada',
            createdAt: '2026-10-18T00:00:00Z',
            classificationStatus: 'COMPLETE',
            attributes: ['area:backend', 'type:coding'],
          };
          if (publishFails)
            return route.fulfill({
              status: 503,
              json: { error: { message: 'Response lost. Retry safely.' } },
            });
          return route.fulfill({ status: 201, json: { data: published } });
        }
        if (path === '/api/knowledge/tickets/' + published?.id)
          return route.fulfill({ json: { data: published } });
        return route.fulfill({ status: 404 });
      });
      await page.goto('/knowledge/new');
      const actions = page.locator('.refinement-options button');
      await expect(actions).toHaveCount(6);
      for (const action of await actions.all())
        await expect(action).toBeDisabled();
      await expect(page.getByRole('radio')).toHaveCount(0);
      await expect(
        page.getByRole('button', { name: 'Publish ticket', exact: true }),
      ).toBeDisabled();
      await expect(
        page.getByRole('complementary').getByRole('button', {
          name: 'Rewrite Title',
          exact: true,
        }),
      ).toBeDisabled();
      await page
        .getByLabel('Title Required', { exact: true })
        .fill('retry safely');
      await page
        .getByLabel('Capture Optional', { exact: true })
        .fill('one stable key');
      await page
        .getByRole('textbox', { name: 'Details Required' })
        .fill('Reuse the payment key on every retry.');
      await page.getByLabel('Writing template').selectOption('Coding');
      await expect(
        page.getByRole('button', { name: 'Publish ticket', exact: true }),
      ).toBeDisabled();
      const editor = page.getByRole('textbox', { name: 'Details Required' });
      await editor.press('End');
      await editor.press('Enter');
      await page.getByRole('button', { name: '</> Code', exact: true }).click();
      await page.keyboard.type('return key;');
      await expect(editor.locator('pre')).toContainText('return key;');
      const codeLanguage = page.getByRole('combobox', {
        name: 'Code language',
        exact: true,
      });
      await codeLanguage.focus();
      await codeLanguage.selectOption('java');
      await expect(codeLanguage).toHaveValue('java');
      await expect(editor.locator('pre code')).toHaveClass(/language-java/);
      await expect(editor.locator('pre .hljs-keyword')).toContainText('return');
      await codeLanguage.selectOption('plaintext');
      await expect(editor.locator('pre [class*="hljs-"]')).toHaveCount(0);
      await codeLanguage.selectOption('java');
      await expect(codeLanguage).toBeFocused();
      await codeLanguage.selectOption('python');
      await expect(codeLanguage).toHaveValue('python');
      await expect(editor.locator('pre code')).toHaveClass(/language-python/);
      await expect(editor.locator('pre .hljs-keyword')).toContainText('return');
      await expect(codeLanguage).toBeFocused();
      await expect(editor.locator('pre')).toContainText('return key;');

      if (viewport.width <= 900) {
        await page
          .getByRole('button', { name: '✧ Refine Title', exact: true })
          .click();
        await expect(
          page.getByRole('group', { name: 'Refine Title', exact: true }),
        ).toBeInViewport();
      }
      await page
        .getByRole('complementary')
        .getByRole('button', { name: 'Proofread Title', exact: true })
        .click();
      await expect(page.getByRole('alert')).toContainText(
        'Your writing is safe',
      );
      await expect(
        page.getByLabel('Title Required', { exact: true }),
      ).toHaveValue('retry safely');
      refineFails = false;
      await page
        .getByRole('complementary')
        .getByRole('button', { name: 'Rephrase Title', exact: true })
        .focus();
      await page.keyboard.press('Enter');
      await expect(
        page.getByRole('heading', { name: 'Suggested wording' }),
      ).toBeVisible();
      await expect(
        page.getByLabel('Title Required', { exact: true }),
      ).toHaveValue('retry safely');
      await page.getByRole('button', { name: 'Discard', exact: true }).click();
      await expect(
        page.getByLabel('Title Required', { exact: true }),
      ).toHaveValue('retry safely');
      await page
        .getByRole('complementary')
        .getByRole('button', { name: 'Rewrite Title', exact: true })
        .click();
      await page.getByRole('button', { name: 'Accept wording' }).click();
      await expect(
        page.getByLabel('Title Required', { exact: true }),
      ).toHaveValue('Retry safely');
      await expect(editor.locator('pre')).toHaveText('return key;');
      await expect(
        page.getByLabel('Capture Optional', { exact: true }),
      ).toHaveValue('one stable key');
      await expect(page.locator('.knowledge-editor .knowledge-ai')).toHaveCount(
        0,
      );
      await expect(
        page.getByRole('button', { name: /Details.*wording|Use Details/ }),
      ).toHaveCount(0);
      await page
        .getByRole('complementary')
        .getByRole('button', { name: 'Proofread Capture', exact: true })
        .click();
      await page.getByRole('button', { name: 'Accept wording' }).click();
      expect(refinements).toEqual([
        { field: 'title', mode: 'proofread' },
        { field: 'title', mode: 'rephrase' },
        { field: 'title', mode: 'rewrite' },
        { field: 'capture', mode: 'proofread' },
      ]);
      await expect(
        page.getByLabel('Capture Optional', { exact: true }),
      ).toBeFocused();
      await expect(
        page.getByLabel('Capture Optional', { exact: true }),
      ).toHaveValue('One stable key.');
      await expect(
        page.getByLabel('Title Required', { exact: true }),
      ).toHaveValue('Retry safely');
      await expect(editor.locator('pre')).toHaveText('return key;');

      page.once('dialog', (dialog) => dialog.dismiss());
      await page.getByRole('link', { name: 'Cancel', exact: true }).click();
      await expect(page).toHaveURL(/knowledge\/new/);
      expect(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= window.innerWidth,
        ),
      ).toBe(true);
      await page
        .getByRole('button', { name: 'Publish ticket', exact: true })
        .click();
      await expect(page.getByRole('alert')).toContainText('Response lost');
      await expect(
        page.getByLabel('Title Required', { exact: true }),
      ).toHaveValue('Retry safely');
      await expect(
        page.getByLabel('Title Required', { exact: true }),
      ).toBeDisabled();
      publishFails = false;
      await page
        .getByRole('button', { name: 'Publish ticket', exact: true })
        .click();
      await expect(page).toHaveURL(/knowledge\/tickets\/ticket-1/);
      expect(ids[0]).toBe(ids[1]);
      await expect(page.getByRole('status')).toContainText([
        'Ticket published',
        'Classification complete',
      ]);
      await expect(
        page.getByRole('heading', { name: 'Retry safely' }),
      ).toBeVisible();
      await expect(page.getByText('Backend', { exact: true })).toBeVisible();
      await expect(page.locator('pre')).toContainText('return key;');
      await page
        .getByRole('link', { name: 'Capture knowledge', exact: true })
        .click();
      await expect(page).toHaveURL(/knowledge\/new$/);
      await expect(
        page.getByLabel('Title Required', { exact: true }),
      ).toHaveValue('');
      await expect(
        page.getByLabel('Capture Optional', { exact: true }),
      ).toHaveValue('');
      await expect(
        page.getByRole('textbox', { name: 'Details Required' }),
      ).toHaveText('');
      await expect(page.getByRole('alert')).toHaveCount(0);
      await expect(
        page.getByRole('button', { name: 'Publish ticket', exact: true }),
      ).toBeDisabled();
      await page
        .getByLabel('Title Required', { exact: true })
        .fill('A second insight');
      await page
        .getByRole('textbox', { name: 'Details Required' })
        .fill('A distinct piece of knowledge.');
      await expect(
        page
          .getByRole('complementary')
          .getByRole('button', { name: 'Rewrite Title', exact: true }),
      ).toBeEnabled();
      await expect(
        page
          .getByRole('complementary')
          .getByRole('button', { name: 'Proofread Capture', exact: true }),
      ).toBeDisabled();

      await page
        .getByRole('button', { name: 'Publish ticket', exact: true })
        .click();
      await expect(page).toHaveURL(/knowledge\/tickets\/ticket-2$/);
      await expect(
        page.getByRole('heading', { name: 'A second insight', exact: true }),
      ).toBeVisible();
      expect(published.content.capture).toBe('');
      await expect(page.locator('.knowledge-ticket-capture')).toHaveCount(0);
      expect(ids).toHaveLength(3);
      expect(ids[2]).not.toBe(ids[0]);

      expect(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= window.innerWidth,
        ),
      ).toBe(true);
    });
  });
}
