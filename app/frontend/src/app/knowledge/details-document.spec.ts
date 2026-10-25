import { Editor } from '@tiptap/core';
import {
  blocksFromDocument,
  documentFromBlocks,
  documentExtensions,
} from './details-document';
import { DetailBlock, emptyBlock, languages } from './knowledge.models';

describe('Details document compatibility', () => {
  it('round trips every supported section without storing HTML', () => {
    const blocks: DetailBlock[] = [
      {
        ...emptyBlock('paragraph'),
        text: '<script>literal text</script>\nSecond line',
      },
      { ...emptyBlock('heading'), text: 'The finding' },
      { ...emptyBlock('bulletList'), text: 'First\nSecond' },
      { ...emptyBlock('orderedList'), text: 'One\nTwo' },
      {
        ...emptyBlock('link'),
        text: 'Reference\nlabel',
        url: 'https://example.com/path',
      },
      ...languages.map((language) => ({
        ...emptyBlock('code'),
        language,
        text: 'return "<html>";\n',
      })),
    ];
    const editor = new Editor({
      extensions: documentExtensions(),
      content: documentFromBlocks(blocks),
    });
    expect(blocksFromDocument(editor.getJSON())).toEqual(blocks);
    editor.destroy();
  });

  it('keeps an empty editable paragraph and normalizes pasted code languages', () => {
    expect(blocksFromDocument(documentFromBlocks([]))).toEqual([
      emptyBlock('paragraph'),
    ]);
    expect(
      blocksFromDocument({
        type: 'doc',
        content: [
          {
            type: 'codeBlock',
            attrs: { language: 'unknown' },
            content: [{ type: 'text', text: 'code' }],
          },
        ],
      }),
    ).toEqual([{ ...emptyBlock('code'), language: 'plaintext', text: 'code' }]);
  });

  it('highlights editing and language changes without persisting decoration markup', () => {
    const code = {
      ...emptyBlock('code'),
      language: 'javascript',
      text: 'const value = "hello";',
    };
    const element = document.createElement('div');
    const editor = new Editor({
      element,
      extensions: documentExtensions(),
      content: documentFromBlocks([code]),
    });
    expect(element.querySelector('.hljs-keyword')?.textContent).toBe('const');
    expect(element.querySelector('.hljs-string')?.textContent).toBe('"hello"');
    editor.commands.setTextSelection(code.text.length + 1);
    editor.commands.insertContent(' // comment');
    expect(element.querySelector('.hljs-comment')?.textContent).toBe('// comment');
    expect(blocksFromDocument(editor.getJSON())).toEqual([
      { ...code, text: code.text + ' // comment' },
      emptyBlock('paragraph'),
    ]);
    editor.commands.updateAttributes('codeBlock', { language: 'plaintext' });
    expect(element.querySelector('[class*="hljs-"]')).toBeNull();
    editor.commands.updateAttributes('codeBlock', { language: 'javascript' });
    expect(element.querySelector('.hljs-keyword')?.textContent).toBe('const');
    editor.destroy();
  });
});
