#!/bin/sh
# Fetches the site-check pages (app/src/test/resources/site-check-urls.txt) into .cache/pages,
# once, for the pages and chef tasks. Pages are other people's content: cached, never committed.
cd "$(dirname "$0")"
mkdir -p .cache/pages
: > .cache/pages/index.txt
n=0
grep -v '^#' ../../app/src/test/resources/site-check-urls.txt | awk '{print $1}' | grep '^http' | while read -r url; do
  n=$((n + 1))
  file=.cache/pages/$(printf %02d "$n").html
  code=$(curl -sL -m 20 -A "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15" -o "$file" -w '%{http_code}' "$url")
  echo "$n $code $url" >> .cache/pages/index.txt
done
cp ../../shared/fixtures/pages/blog-no-recipe-data.html .cache/pages/fixture.html
