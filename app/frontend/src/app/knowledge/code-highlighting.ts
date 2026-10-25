import hljs from 'highlight.js/lib/core';
import java from 'highlight.js/lib/languages/java';
import typescript from 'highlight.js/lib/languages/typescript';
import javascript from 'highlight.js/lib/languages/javascript';
import python from 'highlight.js/lib/languages/python';
import sql from 'highlight.js/lib/languages/sql';
import bash from 'highlight.js/lib/languages/bash';
import json from 'highlight.js/lib/languages/json';
import yaml from 'highlight.js/lib/languages/yaml';
import xml from 'highlight.js/lib/languages/xml';
import css from 'highlight.js/lib/languages/css';
import csharp from 'highlight.js/lib/languages/csharp';
import cpp from 'highlight.js/lib/languages/cpp';
import go from 'highlight.js/lib/languages/go';
import rust from 'highlight.js/lib/languages/rust';
import kotlin from 'highlight.js/lib/languages/kotlin';
import plaintext from 'highlight.js/lib/languages/plaintext';

/** Shared language catalog for editable code and published tickets. */
export const codeGrammars = {
  plaintext,
  java,
  typescript,
  javascript,
  python,
  sql,
  bash,
  json,
  yaml,
  html: xml,
  css,
  csharp,
  cpp,
  go,
  rust,
  kotlin,
};

for (const [name, grammar] of Object.entries(codeGrammars)) {
  hljs.registerLanguage(name, grammar);
}
export { hljs };
