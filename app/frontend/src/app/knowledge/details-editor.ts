import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnDestroy,
  effect,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslocoDirective } from '@jsverse/transloco';
import { Editor, EditorOptions } from '@tiptap/core';
import { closeHistory } from '@tiptap/pm/history';
import { DetailBlock, languages } from './knowledge.models';
import {
  documentExtensions,
  blocksFromDocument,
  documentFromBlocks,
} from './details-document';

@Component({
  selector: 'app-details-editor',
  imports: [FormsModule, TranslocoDirective],
  templateUrl: './details-editor.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DetailsEditor implements AfterViewInit, OnDestroy {
  readonly blocks = input.required<DetailBlock[]>();
  readonly disabled = input(false);
  readonly blocksChange = output<DetailBlock[]>();
  readonly languages = languages;
  readonly host = viewChild.required<ElementRef<HTMLElement>>('canvas');
  readonly linkInput = viewChild<ElementRef<HTMLInputElement>>('linkInput');
  readonly revision = signal(0);
  readonly linking = signal(false);
  readonly linkError = signal(false);
  linkText = '';
  linkUrl = '';
  editor?: Editor;
  private emitted?: DetailBlock[];

  constructor() {
    effect(() => {
      const blocks = this.blocks();
      if (this.editor && blocks !== this.emitted) {
        this.editor.commands.setContent(documentFromBlocks(blocks), {
          emitUpdate: false,
        });
      }
    });
    effect(() => {
      if (this.linking()) this.linkInput()?.nativeElement.focus();
    });
    effect(() => {
      this.editor?.setEditable(!this.disabled(), false);
      if (this.disabled()) this.linking.set(false);
    });
  }

  ngAfterViewInit() {
    this.editor = new Editor({
      element: this.host().nativeElement,
      editable: !this.disabled(),
      extensions: documentExtensions(),
      content: documentFromBlocks(this.blocks()),
      editorProps: this.editorProps(),
      onFocus: () => this.linking.set(false),
      onTransaction: () => this.revision.update((value) => value + 1),
      onUpdate: ({ editor }) => {
        this.emitted = blocksFromDocument(editor.getJSON());
        this.blocksChange.emit(this.emitted);
      },
    });
    this.revision.update((value) => value + 1);
  }

  private editorProps(): EditorOptions['editorProps'] {
    return {
      attributes: {
        role: 'textbox',
        'aria-labelledby': 'details-label',
        'aria-describedby': 'details-hint document-help',
        'aria-multiline': 'true',
        'aria-required': 'true',
        class: 'document-canvas knowledge-prose',
      },
      handleDOMEvents: {
        beforeinput: (view, event) => {
          if (
            event.inputType.startsWith('delete') &&
            !view.state.selection.empty
          ) {
            view.dispatch(closeHistory(view.state.tr));
          }
          return false;
        },
        cut: (view) => {
          view.dispatch(closeHistory(view.state.tr));
          return false;
        },
      },
      handleKeyDown: (view, event) => {
        // A selection deletion must undo independently of the preceding typing.
        if (
          ['Backspace', 'Delete'].includes(event.key) &&
          !view.state.selection.empty
        ) {
          view.dispatch(closeHistory(view.state.tr));
        }
        return false;
      },
      handleClick: (_view, _pos, event) => {
        if ((event.target as HTMLElement).closest('a')) event.preventDefault();
        return false;
      },
    };
  }

  active(type: string): boolean {
    this.revision();
    return this.editor?.isActive(type) ?? false;
  }

  canHistory(action: 'undo' | 'redo'): boolean {
    this.revision();
    return !this.disabled() && !!this.editor?.can()[action]();
  }

  history(action: 'undo' | 'redo') {
    if (!this.disabled()) {
      this.editor?.chain().focus()[action]().run();
      this.editor?.view.focus();
    }
  }

  format(type: string) {
    if (!this.editor || this.disabled()) return;
    this.linking.set(false);
    const chain = this.editor.chain().focus();
    if (type === 'bulletList') chain.toggleBulletList().run();
    else if (type === 'orderedList') chain.toggleOrderedList().run();
    else if (type === 'codeBlock') chain.toggleCodeBlock().run();
    else if (type === 'heading') chain.setNode('heading', { level: 2 }).run();
    else chain.setParagraph().run();
    this.editor.view.focus();
  }

  language(): string {
    this.revision();
    return this.editor?.getAttributes('codeBlock')['language'] ?? 'plaintext';
  }

  setLanguage(language: string) {
    if (!this.disabled()) {
      this.editor
        ?.chain()
        .focus()
        .updateAttributes('codeBlock', { language })
        .run();
    }
  }

  showLink() {
    if (!this.editor || this.disabled()) return;
    const { $from } = this.editor.state.selection;
    this.linkText = $from.parent.textContent;
    this.linkUrl = this.editor.getAttributes('linkedParagraph')['url'] ?? '';
    this.linkError.set(false);
    this.linking.set(!this.linking());
  }

  cancelLink() {
    this.linking.set(false);
    this.editor?.view.focus();
  }

  saveLink() {
    if (!this.editor || this.disabled()) return;
    try {
      const url = new URL(this.linkUrl);
      if (
        !['http:', 'https:'].includes(url.protocol) ||
        url.username ||
        url.password ||
        !this.linkText.trim()
      ) {
        throw new Error('Invalid link');
      }
    } catch {
      this.linkError.set(true);
      return;
    }
    const { $from } = this.editor.state.selection;
    this.editor
      .chain()
      .focus()
      .insertContentAt(
        { from: $from.before(), to: $from.after() },
        {
          type: 'linkedParagraph',
          attrs: { url: this.linkUrl },
          content: [{ type: 'text', text: this.linkText }],
        },
      )
      .run();
    this.linking.set(false);
    this.editor.view.focus();
  }

  ngOnDestroy() {
    this.editor?.destroy();
  }
}
