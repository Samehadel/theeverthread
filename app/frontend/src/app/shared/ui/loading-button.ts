import {
  booleanAttribute,
  ChangeDetectionStrategy,
  Component,
  input,
} from '@angular/core';

/** Adds consistent activity feedback without wrapping the native form control. */
@Component({
  selector: 'button[appLoadingButton]',
  templateUrl: './loading-button.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '[disabled]': 'disabled() || loading()',
    '[class.is-loading]': 'loading()',
    '[attr.aria-busy]': 'loading()',
  },
})
export class LoadingButton {
  readonly loading = input(false, { transform: booleanAttribute });
  readonly disabled = input(false, { transform: booleanAttribute });
}
