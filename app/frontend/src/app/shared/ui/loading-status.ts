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
    '[class.loading-state]': 'active()',
  },
})
export class LoadingStatus {
  readonly active = input(true, { transform: booleanAttribute });
}
