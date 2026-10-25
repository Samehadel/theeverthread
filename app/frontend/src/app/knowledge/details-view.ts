import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
} from '@angular/core';
import { DetailBlock } from './knowledge.models';
import { hljs } from './code-highlighting';
@Component({
  selector: 'app-details-view',
  templateUrl: './details-view.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DetailsView {
  readonly blocks = input.required<DetailBlock[]>();
  readonly rendered = computed(() =>
    this.blocks().map((block) => ({
      ...block,
      highlighted:
        block.type === 'code' && hljs.getLanguage(block.language ?? '')
          ? hljs.highlight(block.text, { language: block.language! }).value
          : null,
    })),
  );
  lines(text: string) {
    return text.split('\n').filter((line) => !!line.trim());
  }
}
