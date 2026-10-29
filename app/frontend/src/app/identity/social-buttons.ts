import { LoadingStatus } from '../shared/ui/loading-status';
import {
  ChangeDetectionStrategy,
  Component,
  HostListener,
  inject,
  DestroyRef,
} from '@angular/core';
import { TranslocoDirective } from '@jsverse/transloco';
import { toSignal } from '@angular/core/rxjs-interop';
import { catchError, of } from 'rxjs';
import { LoadingService } from '../core/loading.service';
import { IdentityApi } from './identity.api';

@Component({
  selector: 'app-social-buttons',
  imports: [LoadingStatus, TranslocoDirective],
  templateUrl: './social-buttons.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SocialButtons {
  private readonly loading = inject(LoadingService);

  private finishNavigation?: () => void;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.finishNavigation?.());
  }

  @HostListener('window:pageshow')
  restorePage() {
    this.finishNavigation?.();
    this.finishNavigation = undefined;
  }

  openProvider(event: MouseEvent) {
    if (!event.ctrlKey && !event.metaKey && !event.shiftKey && !event.altKey) {
      this.finishNavigation ??= this.loading.begin();
    }
  }
  readonly providers = toSignal(
    inject(IdentityApi)
      .providers()
      .pipe(catchError(() => of(null))),
  );
}
