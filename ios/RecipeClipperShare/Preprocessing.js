// The share extension's JavaScript preprocessing file (#35). Safari runs this against the
// page it's sharing, before the extension launches, and hands the result to
// SharedItems.preprocessingResult under NSExtensionJavaScriptPreprocessingResultsKey. Only
// Safari does this (NSExtensionJavaScriptPreprocessingFile in Info.plist); every other app
// shares just the URL, which SharedItems falls back to.
//
// The page never leaves the device: this returns the DOM to the extension's own process, which
// parses it with the same pure parsers the app's fetch uses, instead of fetching the URL again
// (an intermittent bot block is the real coverage gap CLAUDE.md calls out — Safari's copy is
// already past it, with the site's JavaScript run and the user's logins).
var ExtensionPreprocessingJS = function() {};

ExtensionPreprocessingJS.prototype = {
    run: function(arguments) {
        arguments.completionFunction({
            url: document.URL,
            html: document.documentElement.outerHTML
        });
    }
};

var ExtensionPreprocessingJS = new ExtensionPreprocessingJS;
