import { AuthStore } from '../core/auth.store';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  HostListener,
  inject,
  signal,
} from '@angular/core';
import { DOCUMENT, NgTemplateOutlet } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';
import { TranslocoService } from '@jsverse/transloco';
import { KnowledgeStore } from './knowledge.store';
import {
  DetailBlock,
  RefinementField,
  RefinementMode,
  refinementModes,
  TicketContent,
  canRefineField,
  detailsLength,
  emptyBlock,
  templates,
  validContent,
} from './knowledge.models';
import { DetailsEditor } from './details-editor';
@Component({
  providers: [KnowledgeStore],
  selector: 'app-capture-page',
  imports: [
    NgTemplateOutlet,
    FormsModule,
    RouterLink,
    TranslocoDirective,
    DetailsEditor,
  ],
  templateUrl: './capture-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CapturePage {
  readonly auth = inject(AuthStore);
  readonly store = inject(KnowledgeStore);
  private readonly originAccountId = this.auth.account()?.id;
  private readonly originWorkspaceId = this.auth.account()?.workspaceId;
  readonly workspaceChanged = computed(
    () =>
      !this.originWorkspaceId ||
      this.auth.account()?.id !== this.originAccountId ||
      this.auth.account()?.workspaceId !== this.originWorkspaceId,
  );
  readonly content = signal<TicketContent>({
    title: '',
    capture: '',
    template: 'Knowledge',
    details: [emptyBlock('paragraph')],
  });
  readonly refinementModes = refinementModes;
  readonly refinementFields: readonly RefinementField[] = ['title', 'capture'];
  readonly requestedMode = signal<RefinementMode>('rephrase');
  private readonly document = inject(DOCUMENT);
  readonly refinementField = signal<RefinementField | null>(null);
  readonly dirty = signal(false);
  readonly templates = templates;
  readonly length = computed(() => detailsLength(this.content().details));
  readonly valid = computed(() => validContent(this.content()));
  readonly locked = computed(
    () => this.store.busy() || this.store.refining() || this.store.uncertain(),
  );
  private readonly router = inject(Router);
  private readonly translation = inject(TranslocoService);
  private readonly submissionId = crypto.randomUUID();
  constructor() {
    effect(() => {
      const ticket = this.store.ticket();
      if (!ticket) return;
      this.dirty.set(false);
      void this.router.navigate(['/knowledge/tickets', ticket.id], {
        state: { created: true },
      });
    });
  }
  update(field: 'title' | 'capture' | 'template', value: string) {
    this.changed();
    this.content.update((c) => ({ ...c, [field]: value }));
  }
  updateDetails(details: DetailBlock[]) {
    this.changed();
    this.content.update((content) => ({ ...content, details }));
  }
  changed() {
    this.dirty.set(true);
    this.store.discard();
  }
  canRefine(field: RefinementField) {
    return (
      !this.workspaceChanged() &&
      !this.locked() &&
      canRefineField(this.content(), field)
    );
  }
  showRefinement(field: RefinementField) {
    const group = this.document.getElementById(`refinement-${field}`);
    group?.scrollIntoView({ block: 'start' });
    group?.focus({ preventScroll: true });
  }
  originalFor(field: RefinementField) {
    return this.content()[field] ?? '';
  }
  refine(field: RefinementField, mode: RefinementMode) {
    if (this.canRefine(field)) {
      this.refinementField.set(field);
      this.requestedMode.set(mode);
      this.store.refine({
        content: this.content(),
        field,
        mode,
        workspaceId: this.originWorkspaceId!,
      });
    }
  }
  discardRefinement() {
    this.store.discard();
    this.document
      .getElementById(
        `refine-${this.refinementField()}-${this.requestedMode()}`,
      )
      ?.focus();
  }
  accept() {
    const suggestion = this.store.suggestion();
    if (!suggestion || this.workspaceChanged()) return;
    this.content.update((c) => ({ ...c, [suggestion.field]: suggestion.text }));
    this.changed();
    this.document.getElementById(`ticket-${suggestion.field}`)?.focus();
  }
  publish() {
    if (
      !this.workspaceChanged() &&
      this.valid() &&
      !this.store.busy() &&
      !this.store.refining()
    )
      this.store.publish({
        id: this.submissionId,
        content: this.content(),
        workspaceId: this.originWorkspaceId!,
      });
  }
  canLeave() {
    return (
      !this.dirty() ||
      window.confirm(this.translation.translate('knowledge.leave'))
    );
  }
  @HostListener('window:beforeunload', ['$event'])
  beforeUnload(event: BeforeUnloadEvent) {
    if (this.dirty()) {
      event.preventDefault();
      event.returnValue = '';
    }
  }
}
