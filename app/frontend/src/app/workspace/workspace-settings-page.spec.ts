import { TestBed } from '@angular/core/testing';
import { of, Subject, throwError } from 'rxjs';
import { AuthStore } from '../core/auth.store';
import { Account, IdentityApi } from '../identity/identity.api';
import { WorkspaceSettingsPage } from './workspace-settings-page';

const original: Account = {
  id: '1',
  fullName: 'Ada',
  email: 'ada@example.com',
  workspaceId: '10',
  workspaceName: 'Design',
  accessRole: 'OWNER',
  defaultWorkspaceId: '10',
  workspaces: [
    { id: '10', name: 'Design', role: 'OWNER' },
    { id: '20', name: 'Team', role: 'MEMBER' },
    { id: '30', name: 'Research', role: 'OWNER' },
  ],
};

function setup() {
  const api = { createWorkspace: jest.fn(), renameWorkspace: jest.fn() };
  TestBed.configureTestingModule({
    providers: [{ provide: IdentityApi, useValue: api }],
  });
  const auth = TestBed.inject(AuthStore);
  auth.setAccount(original);
  const page = TestBed.runInInjectionContext(() => new WorkspaceSettingsPage());
  return { page, api, auth };
}

describe('Workspace management', () => {
  it('adds a new workspace to the switcher and preserves the selected context', async () => {
    const { page, api, auth } = setup();
    api.createWorkspace.mockReturnValue(
      of({
        ...original,
        workspaceId: '40',
        workspaceName: 'New space',
        workspaces: [
          ...original.workspaces,
          { id: '40', name: 'New space', role: 'OWNER' },
        ],
      }),
    );
    page.createForm.controls.name.setValue('  New space  ');
    await page.save('create');
    expect(api.createWorkspace).toHaveBeenCalledWith('New space');
    expect(auth.account()?.workspaceId).toBe('10');
    expect(auth.account()?.workspaceName).toBe('Design');
    expect(auth.account()?.workspaces).toHaveLength(4);
    expect(page.createForm.controls.name.value).toBe('');
    expect(page.success()).toBe('workspaceSettings.created');
  });

  it.each(['10', '30'])(
    'renames owned workspace %s and keeps the selected context consistent',
    async (id) => {
      const { page, api, auth } = setup();
      const workspace = original.workspaces.find((item) => item.id === id)!;
      page.edit(workspace);
      page.renameForm.controls.name.setValue('Renamed');
      api.renameWorkspace.mockReturnValue(
        of({
          ...original,
          workspaceId: id,
          workspaceName: 'Renamed',
          workspaces: original.workspaces.map((item) =>
            item.id === id ? { ...item, name: 'Renamed' } : item,
          ),
        }),
      );
      await page.save('rename');
      expect(api.renameWorkspace).toHaveBeenCalledWith(id, 'Renamed');
      expect(auth.account()?.workspaceId).toBe('10');
      expect(auth.account()?.workspaceName).toBe(
        id === '10' ? 'Renamed' : 'Design',
      );
      expect(
        auth.account()?.workspaces.find((item) => item.id === id)?.name,
      ).toBe('Renamed');
      expect(page.editing()).toBeNull();
    },
  );

  it('keeps the draft and displays a useful duplicate-name error from the backend', async () => {
    const { page, api, auth } = setup();
    api.createWorkspace.mockReturnValue(
      throwError(() => ({
        status: 409,
        error: { error: { code: 'WORKSPACE_NAME_TAKEN' } },
      })),
    );
    page.createForm.controls.name.setValue('design');
    await page.save('create');
    expect(page.error()).toBe('workspaceSettings.duplicate');
    expect(page.createForm.controls.name.value).toBe('design');
    expect(auth.account()).toEqual(original);
    expect(page.busy()).toBe(false);
  });

  it.each(['', '   ', 'a'.repeat(201)])(
    'rejects invalid name %s',
    async (name) => {
      const { page, api } = setup();
      page.createForm.controls.name.setValue(name);
      await page.save('create');
      expect(api.createWorkspace).not.toHaveBeenCalled();
      expect(page.createForm.controls.name.touched).toBe(true);
    },
  );

  it('prevents editing a member workspace', async () => {
    const { page, api } = setup();
    page.edit(original.workspaces[1]);
    page.renameForm.controls.name.setValue('Changed');
    await page.save('rename');
    expect(api.renameWorkspace).not.toHaveBeenCalled();
  });

  it('allows only one pending save and recovers after a failed request', async () => {
    const { page, api } = setup();
    const pending = new Subject<Account>();
    api.createWorkspace.mockReturnValue(pending);
    page.createForm.controls.name.setValue('New space');
    const saving = page.save('create');
    await page.save('create');
    expect(api.createWorkspace).toHaveBeenCalledTimes(1);
    expect(page.busy()).toBe(true);
    pending.error(new Error('Network unavailable'));
    await saving;
    expect(page.busy()).toBe(false);
    expect(page.error()).toBe('workspaceSettings.saveFailed');
    expect(page.createForm.controls.name.value).toBe('New space');
  });
});
