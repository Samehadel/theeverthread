import { HttpContextToken, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { defer, finalize } from 'rxjs';
import { LoadingService } from './loading.service';

export const BACKGROUND_REQUEST = new HttpContextToken(() => false);

export const loadingInterceptor: HttpInterceptorFn = (request, next) => {
  if (
    !request.url.startsWith('/api/') ||
    request.context.get(BACKGROUND_REQUEST)
  ) {
    return next(request);
  }
  const loading = inject(LoadingService);
  return defer(() => {
    const finish = loading.begin();
    return defer(() => next(request)).pipe(finalize(finish));
  });
};
