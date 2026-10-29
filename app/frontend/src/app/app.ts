import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import {
  NavigationStart,
  NavigationEnd,
  NavigationCancel,
  NavigationError,
  NavigationSkipped,
  Router,
  RouterOutlet,
} from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TranslocoDirective } from '@jsverse/transloco';
import { LoadingService } from './core/loading.service';
import { WorkspaceShell } from './workspace/workspace-shell';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, WorkspaceShell, TranslocoDirective],
  templateUrl: './app.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {
  readonly loading = inject(LoadingService);

  constructor() {
    const navigation = new Map<number, () => void>();
    inject(Router)
      .events.pipe(takeUntilDestroyed())
      .subscribe((event) => {
        if (event instanceof NavigationStart) {
          navigation.set(event.id, this.loading.begin());
        } else if (
          event instanceof NavigationEnd ||
          event instanceof NavigationCancel ||
          event instanceof NavigationError ||
          event instanceof NavigationSkipped
        ) {
          navigation.get(event.id)?.();
          navigation.delete(event.id);
        }
      });
  }
}
