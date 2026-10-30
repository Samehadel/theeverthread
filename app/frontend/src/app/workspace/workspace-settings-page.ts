import { LoadingStatus } from '../shared/ui/loading-status';
import { LoadingButton } from '../shared/ui/loading-button';
import {
  ChangeDetectionStrategy,
  Component,
  inject,
  signal,
} from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';
import { AuthStore } from '../core/auth.store';
import { IdentityApi } from '../identity/identity.api';

@Component({
  selector: 'app-workspace-settings-page',
  imports: [
    LoadingButton,
    LoadingStatus,
    ReactiveFormsModule,
    RouterLink,
    TranslocoDirective,
  ],
  templateUrl: './workspace-settings-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WorkspaceSettingsPage {
  readonly auth = inject(AuthStore);
  private readonly api = inject(IdentityApi);
  private readonly fb = inject(FormBuilder).nonNullable;
  readonly busy = signal(false);
  readonly error = signal('');
  readonly errorForm = signal<'create' | 'rename' | null>(null);
  readonly success = signal('');
  readonly editing = signal<string | null>(null);
  readonly createForm = this.nameForm();
  readonly renameForm = this.nameForm();

  load() {
    this.auth.load().subscribe();
  }

  private nameForm() {
    return this.fb.group({
      name: [
        '',
        [
          Validators.required,
          Validators.maxLength(200),
          Validators.pattern(/\S/),
        ],
      ],
    });
  }

  edit(workspace: { id: string; name: string }) {
    this.editing.set(workspace.id);
    this.renameForm.reset({ name: workspace.name });
    this.error.set('');
    this.success.set('');
  }

  cancel() {
    this.editing.set(null);
    this.error.set('');
  }

  async save(kind: 'create' | 'rename') {
    const form = kind === 'create' ? this.createForm : this.renameForm;
    form.markAllAsTouched();
    if (this.busy() || form.invalid) return;
    const id = this.editing();
    if (
      kind === 'rename' &&
      (!id ||
        !this.auth
          .account()
          ?.workspaces.some(
            (workspace) => workspace.id === id && workspace.role === 'OWNER',
          ))
    )
      return;
    this.busy.set(true);
    this.errorForm.set(kind);
    this.error.set('');
    this.success.set('');
    try {
      const name = form.controls.name.value.trim();
      const account = await firstValueFrom(
        kind === 'create'
          ? this.api.createWorkspace(name)
          : this.api.renameWorkspace(id!, name),
      );
      // Management updates the picker without changing the currently selected workspace.
      this.auth.restoreAccount(account);
      form.reset();
      if (kind === 'rename') this.editing.set(null);
      this.success.set(
        kind === 'create'
          ? 'workspaceSettings.created'
          : 'workspaceSettings.renamed',
      );
    } catch (error: unknown) {
      const code = (error as { error?: { error?: { code?: string } } })?.error
        ?.error?.code;
      this.error.set(
        code === 'WORKSPACE_NAME_TAKEN'
          ? 'workspaceSettings.duplicate'
          : 'workspaceSettings.saveFailed',
      );
    } finally {
      this.busy.set(false);
    }
  }
}
