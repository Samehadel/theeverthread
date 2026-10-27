import { inject } from '@angular/core';
import { patchState, signalStore, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { catchError, EMPTY, pipe, switchMap, tap } from 'rxjs';
import { KnowledgeApi } from './knowledge.api';

/** Shared product labels are refreshed on entry; content remains usable if labels are unavailable. */
export const AttributeCatalogStore = signalStore(
  { providedIn: 'root' },
  withState({ labels: {} as Record<string, string> }),
  withMethods((store, api = inject(KnowledgeApi)) => ({
    refresh: rxMethod<void>(
      pipe(
        switchMap(() =>
          api.attributes().pipe(
            tap((labels) => patchState(store, { labels })),
            catchError(() => EMPTY),
          ),
        ),
      ),
    ),
  })),
);
