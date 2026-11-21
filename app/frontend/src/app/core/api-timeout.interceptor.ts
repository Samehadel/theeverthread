import { HttpInterceptorFn } from '@angular/common/http';
import { timeout } from 'rxjs';

export const apiTimeoutInterceptor: HttpInterceptorFn = (request, next) => {
  if (!request.url.startsWith('/api/')) return next(request);

  const deadline =
    request.url === '/api/knowledge/tickets/refine'
      ? 50000
      : request.url.startsWith('/api/registration') ||
          request.url.startsWith('/api/invitations')
        ? 15000
        : 5000;
  return next(request).pipe(timeout(deadline));
};
