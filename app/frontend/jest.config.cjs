const { createCjsPreset } = require('jest-preset-angular/presets');
module.exports = {
  ...createCjsPreset(),
  // Transloco and Lowlight publish ESM .js dependencies alongside Angular's .mjs files.
  transformIgnorePatterns: ['node_modules/(?!(.*\\.mjs$|@angular/common/locales/.*\\.js$|@jsverse/|lowlight/|devlop/))'],
  setupFilesAfterEnv: ['<rootDir>/setup-jest.ts'],
  testMatch: ['<rootDir>/src/**/*.spec.ts'],
};
