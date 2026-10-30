import { LoadingStatus } from '../shared/ui/loading-status';
import { LoadingButton } from '../shared/ui/loading-button';
import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';
import { LinkAccountStore } from './link-account.store';

@Component({
  selector: 'app-link-account-page',
  imports: [LoadingButton, LoadingStatus, RouterLink, TranslocoDirective],
  providers: [LinkAccountStore],
  templateUrl: './link-account-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LinkAccountPage {
  readonly store = inject(LinkAccountStore);
  constructor() {
    this.store.load();
  }
}
