import {
  booleanAttribute,
  ChangeDetectionStrategy,
  Component,
  input,
} from '@angular/core';

/** Inline progress message; callers supply localized, operation-specific text. */
@Component({
  selector: 'p[appLoadingStatus]',
  templateUrl: './loading-status.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    role: 'status',
    'aria-live': 'polite',
    '[class.loading-state]': "active() && appearance() === 'inline'",
    '[class.loading-surface]': "active() && appearance() !== 'inline'",
    '[class.loading-document]': "appearance() === 'document'",
    '[class.loading-ai]': "appearance() === 'ai'",
  },
})
export class LoadingStatus {
  readonly appearance = input<'inline' | 'document' | 'list' | 'ai'>('inline');
  readonly hint = input('');
  readonly active = input(true, { transform: booleanAttribute });
}
