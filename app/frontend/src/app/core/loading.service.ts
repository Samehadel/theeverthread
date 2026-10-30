import { computed, Injectable, signal } from '@angular/core';

/** Tracks overlapping work and bridges short gaps between chained requests. */
@Injectable({ providedIn: 'root' })
export class LoadingService {
  private readonly pending = signal(0);
  private readonly visible = signal(false);
  private showTimer?: ReturnType<typeof setTimeout>;
  private hideTimer?: ReturnType<typeof setTimeout>;
  readonly active = computed(() => this.visible());

  begin(): () => void {
    clearTimeout(this.hideTimer);
    this.pending.update((count) => count + 1);
    if (!this.visible() && !this.showTimer) {
      this.showTimer = setTimeout(() => {
        if (this.pending() > 0) this.visible.set(true);
        this.showTimer = undefined;
      }, 120);
    }
    let finished = false;
    return () => {
      if (finished) return;
      finished = true;
      this.pending.update((count) => count - 1);
      if (this.pending() === 0) {
        this.hideTimer = setTimeout(() => {
          clearTimeout(this.showTimer);
          this.showTimer = undefined;
          this.visible.set(false);
        }, 200);
      }
    };
  }
}
