import { JSONContent, Node, mergeAttributes } from '@tiptap/core';
import StarterKit from '@tiptap/starter-kit';
import { ListItem } from '@tiptap/extension-list';
import { DetailBlock, emptyBlock, languages } from './knowledge.models';

/** A linked paragraph keeps the existing ticket contract without storing HTML. */
export const LinkedParagraph = Node.create({
  name: 'linkedParagraph',
  group: 'block',
  content: 'inline*',
  marks: '',
  addAttributes() {
    return { url: { default: '' } };
  },
  parseHTML() {
    return [{ tag: 'p[data-linked-paragraph]' }];
  },
  renderHTML({ HTMLAttributes }) {
    return [
      'p',
      mergeAttributes(HTMLAttributes, { 'data-linked-paragraph': '' }),
      ['a', { href: safeLink(HTMLAttributes['url']), tabindex: '-1' }, 0],
    ];
  },
  addKeyboardShortcuts() {
    return {
      Enter: () =>
        this.editor.isActive('linkedParagraph') &&
        this.editor.chain().splitBlock().setParagraph().run(),
    };
  },
});

/** Enable only structures that the ticket contract can preserve. */
export function documentExtensions() {
  return [
    StarterKit.configure({
      heading: { levels: [2] },
      bold: false,
      italic: false,
      strike: false,
      underline: false,
      code: false,
      link: false,
      blockquote: false,
      horizontalRule: false,
      listItem: false,
      orderedList: { keepAttributes: false },
      codeBlock: { defaultLanguage: 'plaintext' },
      undoRedo: { depth: 200 },
    }),
    // Nested lists cannot be represented in the current ticket contract.
    ListItem.extend({
      content: 'paragraph',
      addKeyboardShortcuts() {
        return {
          ...this.parent?.(),
          'Shift-Enter': () => this.editor.commands.splitListItem('listItem'),
        };
      },
    }),
    LinkedParagraph,
  ];
}

function safeLink(value: string): string | null {
  try {
    const url = new URL(value);
    return ['http:', 'https:'].includes(url.protocol) &&
      !url.username &&
      !url.password
      ? value
      : null;
  } catch {
    return null;
  }
}

function inline(text: string): JSONContent[] {
  return text
    .split('\n')
    .flatMap((line, index) => [
      ...(index ? [{ type: 'hardBreak' }] : []),
      ...(line ? [{ type: 'text', text: line }] : []),
    ]);
}

export function documentFromBlocks(blocks: DetailBlock[]): JSONContent {
  return {
    type: 'doc',
    content: blocks.length
      ? blocks.map((block): JSONContent => {
          if (block.type === 'bulletList' || block.type === 'orderedList') {
            return {
              type: block.type,
              content: block.text.split('\n').map((text) => ({
                type: 'listItem',
                content: [{ type: 'paragraph', content: inline(text) }],
              })),
            };
          }
          if (block.type === 'code') {
            return {
              type: 'codeBlock',
              attrs: { language: block.language },
              content: block.text ? [{ type: 'text', text: block.text }] : [],
            };
          }
          return {
            type: block.type === 'link' ? 'linkedParagraph' : block.type,
            attrs: block.type === 'heading' ? { level: 2 } : { url: block.url },
            content: inline(block.text),
          };
        })
      : [{ type: 'paragraph' }],
  };
}

function textOf(node: JSONContent): string {
  if (node.type === 'hardBreak') return '\n';
  return node.text ?? (node.content ?? []).map(textOf).join('');
}

/** Serialize only supported structures; raw pasted HTML never enters the API. */
export function blocksFromDocument(doc: JSONContent): DetailBlock[] {
  return (doc.content ?? []).map((node): DetailBlock => {
    const type =
      node.type === 'codeBlock'
        ? 'code'
        : node.type === 'linkedParagraph'
          ? 'link'
          : (node.type as DetailBlock['type']);
    const block = { ...emptyBlock(type), text: textOf(node) };
    if (type === 'bulletList' || type === 'orderedList') {
      block.text = (node.content ?? []).map(textOf).join('\n');
    }
    if (type === 'code') {
      const language = node.attrs?.['language'];
      block.language = languages.includes(language) ? language : 'plaintext';
    }
    if (type === 'link') block.url = node.attrs?.['url'] ?? '';
    return block;
  });
}
