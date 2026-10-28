import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { AttributeCatalogStore } from './attribute-catalog.store';

describe('Database attribute labels', () => {
  let store: InstanceType<typeof AttributeCatalogStore>;
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    store = TestBed.inject(AttributeCatalogStore);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('loads new labels and picks up database edits on refresh', () => {
    store.refresh();
    http
      .expectOne('/api/knowledge/tickets/attributes')
      .flush({ data: { 'area:gardening': 'Gardening' } });
    expect(store.labels()['area:gardening']).toBe('Gardening');
    store.refresh();
    http
      .expectOne('/api/knowledge/tickets/attributes')
      .flush({ data: { 'area:gardening': 'Garden projects' } });
    expect(store.labels()['area:gardening']).toBe('Garden projects');
  });

  it('preserves loaded labels on failure and allows retry', () => {
    store.refresh();
    http
      .expectOne('/api/knowledge/tickets/attributes')
      .flush({ data: { 'area:gardening': 'Gardening' } });
    store.refresh();
    http
      .expectOne('/api/knowledge/tickets/attributes')
      .flush({}, { status: 503, statusText: 'Unavailable' });
    expect(store.labels()['area:gardening']).toBe('Gardening');
    store.refresh();
    http.expectOne('/api/knowledge/tickets/attributes').flush({ data: {} });
    expect(store.labels()).toEqual({});
  });
});
