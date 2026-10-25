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
});
