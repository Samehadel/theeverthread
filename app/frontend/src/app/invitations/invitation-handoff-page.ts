import { LoadingStatus } from '../shared/ui/loading-status';
import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';
import { InvitationPage } from './invitation-page';
@Component({
  selector: 'app-invitation-handoff-page',
  imports: [LoadingStatus, RouterLink, TranslocoDirective],
  templateUrl: './invitation-handoff-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class InvitationHandoffPage extends InvitationPage {}
