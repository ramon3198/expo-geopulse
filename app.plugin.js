// Entry point Expo uses to resolve this package's config plugin.
// The plugin source lives in `plugin/src` and is compiled to `plugin/build`.
module.exports = require('./plugin/build').default;
