import { test, expect } from '@playwright/test';

test('public landing page remains independent of backend availability', async ({
  page,
}) => {
  const apiRequests: string[] = [];
  await page.route('**/api/**', (route) => {
    apiRequests.push(new URL(route.request().url()).pathname);
    return route.abort();
  });
  await page.goto('/');
  await expect(page.getByRole('heading', { level: 1 })).toContainText(
    'Keep what you learn.',
  );
  await page.keyboard.press('Tab');
  await expect(
    page.getByRole('link', { name: 'Skip to content' }),
  ).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('main')).toBeFocused();
  await page
    .getByRole('link', { name: 'Explore the vision', exact: true })
    .first()
    .click();
  await expect(page).toHaveURL(/#workflow$/);
  await expect(
    page.getByRole('region', { name: 'Illustrative product preview' }),
  ).toBeInViewport();
  expect(apiRequests).toEqual(['/api/auth/me']);
  await page.getByRole('link', { name: 'Workspaces', exact: true }).click();
  await expect(page).toHaveURL(/#workspaces$/);
  await expect(
    page.getByRole('heading', {
      name: 'One account. Space for every context.',
    }),
  ).toBeInViewport();
  await expect(
    page.getByText('Each creator keeps control', { exact: false }),
  ).toBeVisible();
  await expect(page).toHaveTitle(
    'theeverthread — Keep what you learn. Build on it every day.',
  );
  for (const width of [320, 390, 768, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
  }
});

test('landing signup actions use the same account journey', async ({
  page,
}) => {
  await page.route('**/api/**', (route) => route.abort());
  await page.goto('/');
  const signupLinks = page.locator('a[href="/signup"]');
  await expect(signupLinks).toHaveCount(3);
  await expect(
    page.getByRole('link', { name: 'Log in', exact: true }),
  ).toHaveAttribute('href', '/login');
  await signupLinks.nth(1).click();
  await expect(page).toHaveURL(/\/signup$/);
  await expect(
    page.getByRole('heading', { name: 'Create account', level: 1 }),
  ).toBeVisible();
});
