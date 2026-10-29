import { TestBed } from '@angular/core/testing';
import {
  HttpClient,
  HttpContext,
  provideHttpClient,
  withInterceptors,
} from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { BACKGROUND_REQUEST, loadingInterceptor } from './loading.interceptor';
import { LoadingService } from './loading.service';

describe('Application loading feedback', () => {
  let http: HttpClient;
  let requests: HttpTestingController;
  let loading: LoadingService;

  beforeEach(() => {
    jest.useFakeTimers();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([loadingInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    requests = TestBed.inject(HttpTestingController);
    loading = TestBed.inject(LoadingService);
  });

  afterEach(() => {
    requests.verify();
    jest.clearAllTimers();
    jest.useRealTimers();
  });

  it('stays visible until overlapping requests finish, including failures', () => {
    http.get('/api/one').subscribe();
    http.get('/api/two').subscribe({ error: () => {} });
    jest.advanceTimersByTime(120);
    expect(loading.active()).toBe(true);
    requests.expectOne('/api/one').flush({});
    jest.advanceTimersByTime(300);
    expect(loading.active()).toBe(true);
    requests
      .expectOne('/api/two')
      .flush({}, { status: 503, statusText: 'Unavailable' });
    jest.advanceTimersByTime(200);
    expect(loading.active()).toBe(false);
  });

  it('cleans up when a request is cancelled', () => {
    const subscription = http.get('/api/one').subscribe();
    requests.expectOne('/api/one');
    jest.advanceTimersByTime(120);
    expect(loading.active()).toBe(true);
    subscription.unsubscribe();
    jest.advanceTimersByTime(200);
    expect(loading.active()).toBe(false);
  });

  it('does not flash for fast responses or background polling', () => {
    http.get('/api/fast').subscribe();
    requests.expectOne('/api/fast').flush({});
    jest.advanceTimersByTime(500);
    expect(loading.active()).toBe(false);
    http
      .get('/api/poll', {
        context: new HttpContext().set(BACKGROUND_REQUEST, true),
      })
      .subscribe();
    http.get('/i18n/en.json').subscribe();
    jest.advanceTimersByTime(500);
    expect(loading.active()).toBe(false);
    requests.expectOne('/api/poll').flush({});
    requests.expectOne('/i18n/en.json').flush({});
  });

  it('bridges chained operations without flicker and finishes each operation once', () => {
    const finish = loading.begin();
    jest.advanceTimersByTime(120);
    finish();
    finish();
    jest.advanceTimersByTime(100);
    const finishNext = loading.begin();
    jest.advanceTimersByTime(300);
    expect(loading.active()).toBe(true);
    finishNext();
    jest.advanceTimersByTime(200);
    expect(loading.active()).toBe(false);
  });
});
