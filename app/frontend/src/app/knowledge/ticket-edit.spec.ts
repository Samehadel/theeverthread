import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap, Router } from '@angular/router';
import { TranslocoService } from '@jsverse/transloco';
import { AuthStore } from '../core/auth.store';
import { Account } from '../identity/identity.api';
import { CapturePage } from './capture-page';
import { KnowledgeStore } from './knowledge.store';
import { Ticket } from './knowledge.models';

const ticket: Ticket = {
  id: 'ticket',
  authorId: 'author',
  authorName: 'Ada',
  createdAt: '2026-11-17T12:00:00Z',
  version: 2,
  classificationStatus: 'COMPLETE',
  attributes: ['area:backend'],
  content: {
    title: 'Original title',
    capture: 'Original capture',
    template: 'Knowledge',
    details: [
      {
        type: 'paragraph',
        text: 'Original details',
        url: null,
        language: null,
      },
    ],
  },
};

describe('Editing a published ticket', () => {
  let http: HttpTestingController;
  let auth: InstanceType<typeof AuthStore>;
  let store: InstanceType<typeof KnowledgeStore>;
  const navigate = jest.fn();
  beforeEach(() => {
    navigate.mockReset();
    TestBed.configureTestingModule({
      providers: [
        KnowledgeStore,
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: Router, useValue: { navigate } },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { paramMap: convertToParamMap({ id: 'ticket' }) },
          },
        },
        {
          provide: TranslocoService,
          useValue: { translate: (key: string) => key },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthStore);
    auth.setAccount({ id: 'author', workspaceId: 'workspace' } as Account);
    store = TestBed.inject(KnowledgeStore);
  });
  afterEach(() => http.verify());
  function open(loaded = ticket) {
    const page = TestBed.runInInjectionContext(() => new CapturePage());
    page.publish();
    http.expectNone('/api/auth/csrf');
    http.expectOne('/api/knowledge/tickets/ticket').flush({ data: loaded });
    TestBed.tick();
    return page;
  }
  function saveRequest() {
    http
      .expectOne('/api/auth/csrf')
      .flush({ data: { token: 'token', headerName: 'X-CSRF-TOKEN' } });
    const request = http.expectOne('/api/knowledge/tickets/ticket');
    expect(request.request.method).toBe('PUT');
    expect(request.request.headers.get('X-Workspace-Id')).toBe('workspace');
    expect(request.request.headers.get('X-CSRF-TOKEN')).toBe('token');
    return request;
  }
  it('loads the existing editor and saves with its version before navigating to the updated ticket', () => {
    const page = open();
    expect(page.content()).toEqual(ticket.content);
    expect(page.dirty()).toBe(false);
    expect(navigate).not.toHaveBeenCalled();
    page.update('title', 'Edited title');
    page.publish();
    const request = saveRequest();
    expect(request.request.body).toEqual({
      version: 2,
      content: page.content(),
    });
    request.flush({ data: { ...ticket, version: 3, content: page.content() } });
    TestBed.tick();
    expect(page.dirty()).toBe(false);
    expect(navigate).toHaveBeenCalledWith(['/knowledge/tickets', 'ticket'], {
      state: { created: false, updated: true },
    });
  });
  it('denies another creator and prevents saving after workspace or account changes', () => {
    const page = open({ ...ticket, authorId: 'someone-else' });
    expect(page.editForbidden()).toBe(true);
    expect(page.initialized()).toBe(false);
    page.publish();
    http.expectNone('/api/auth/csrf');
  });
  it('keeps edits on a conflict, guards navigation, and sends no writes after a workspace switch', () => {
    const page = open();
    page.update('title', 'My unsaved title');
    const confirm = jest.spyOn(window, 'confirm').mockReturnValue(false);
    expect(page.canLeave()).toBe(false);
    confirm.mockRestore();
    page.publish();
    saveRequest().flush(
      { error: { code: 'TICKET_EDIT_CONFLICT', message: 'Ticket changed' } },
      { status: 409, statusText: 'Conflict' },
    );
    TestBed.tick();
    expect(page.content().title).toBe('My unsaved title');
    expect(page.dirty()).toBe(true);
    expect(store.error()).toBe('Ticket changed');
    expect(navigate).not.toHaveBeenCalled();
    auth.setAccount({ id: 'author', workspaceId: 'other' } as Account);
    page.publish();
    page.refine('title', 'rewrite');
    http.expectNone('/api/auth/csrf');
  });
  it('locks an uncertain save and retries the same version and content', () => {
    const page = open();
    page.update('title', 'Saved but response lost');
    page.publish();
    const first = saveRequest();
    const body = first.request.body;
    first.error(new ProgressEvent('error'));
    expect(page.locked()).toBe(true);
    expect(store.uncertain()).toBe(true);
    page.publish();
    const retry = saveRequest();
    expect(retry.request.body).toEqual(body);
    retry.flush({ data: { ...ticket, version: 3, content: page.content() } });
    TestBed.tick();
    expect(navigate).toHaveBeenCalledTimes(1);
  });
  it('does not retarget a save while waiting for CSRF', () => {
    const page = open();
    page.update('title', 'Private edit');
    page.publish();
    auth.setAccount({
      id: 'different-account',
      workspaceId: 'workspace',
    } as Account);
    http
      .expectOne('/api/auth/csrf')
      .flush({ data: { token: 'token', headerName: 'X-CSRF-TOKEN' } });
    http.expectNone('/api/knowledge/tickets/ticket');
    expect(page.content().title).toBe('Private edit');
    expect(navigate).not.toHaveBeenCalled();
  });
});
