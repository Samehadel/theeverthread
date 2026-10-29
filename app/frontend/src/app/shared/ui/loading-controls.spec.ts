import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { LoadingButton } from './loading-button';
import { LoadingStatus } from './loading-status';

@Component({
  imports: [LoadingButton, LoadingStatus],
  template: `
    <form (submit)="save($event)">
      <button
        appLoadingButton
        type="submit"
        [loading]="busy()"
        [disabled]="invalid()"
        aria-label="Save draft"
      >
        <span aria-hidden="true">＋</span><span>Save</span>
      </button>
    </form>
    <p appLoadingStatus [active]="busy()">Saving your draft…</p>
  `,
})
class TestPage {
  readonly busy = signal(false);
  readonly invalid = signal(false);
  submissions = 0;

  save(event: Event) {
    event.preventDefault();
    this.submissions++;
  }
}

describe('Shared loading controls', () => {
  function render() {
    const fixture = TestBed.createComponent(TestPage);
    fixture.detectChanges();
    return {
      fixture,
      page: fixture.componentInstance,
      button: fixture.nativeElement.querySelector(
        'button',
      ) as HTMLButtonElement,
      status: fixture.nativeElement.querySelector('p') as HTMLParagraphElement,
    };
  }

  it('preserves the native submit button, accessible label, and projected content', () => {
    const { page, button } = render();
    expect(button.type).toBe('submit');
    expect(button.getAttribute('aria-label')).toBe('Save draft');
    expect(button.textContent).toContain('Save');
    expect(button.querySelector('[aria-hidden="true"]')?.textContent).toBe(
      '＋',
    );
    button.click();
    expect(page.submissions).toBe(1);
  });

  it('prevents duplicate submissions while loading and preserves validation disabling afterward', () => {
    const { fixture, page, button } = render();
    page.busy.set(true);
    fixture.detectChanges();
    expect(button.disabled).toBe(true);
    expect(button.classList.contains('is-loading')).toBe(true);
    expect(button.getAttribute('aria-busy')).toBe('true');
    button.click();
    expect(page.submissions).toBe(0);

    page.busy.set(false);
    page.invalid.set(true);
    fixture.detectChanges();
    expect(button.disabled).toBe(true);
    expect(button.classList.contains('is-loading')).toBe(false);
    expect(button.getAttribute('aria-busy')).toBe('false');

    page.invalid.set(false);
    fixture.detectChanges();
    expect(button.disabled).toBe(false);
    button.click();
    expect(page.submissions).toBe(1);
  });

  it('announces localized caller content and only shows the inline spinner while active', () => {
    const { fixture, page, status } = render();
    expect(status.getAttribute('role')).toBe('status');
    expect(status.getAttribute('aria-live')).toBe('polite');
    expect(status.textContent).toContain('Saving your draft…');
    expect(status.classList.contains('loading-state')).toBe(false);
    page.busy.set(true);
    fixture.detectChanges();
    expect(status.classList.contains('loading-state')).toBe(true);
  });
});
