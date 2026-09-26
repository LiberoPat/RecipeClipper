#!/bin/sh
# Builds and runs the #105 evaluation harness. See tools/eval/README (in docs/eval/llm-vs-regex.md).
# Needs: Xcode's swiftc; for the LLM arms, `ollama serve` with qwen2.5:3b pulled, and for the
# System One arm `llama-server --hf-repo alibiserikbay/JevK5-GGUF --hf-file jevk5-4b-v0.3-Q8_0.gguf
# -c 8192 -ngl 99 --port 8090`. Pages come from `fetch-pages.sh` (cached, not committed).
set -e
cd "$(dirname "$0")"
APP=../../ios/RecipeClipper
mkdir -p .build
rm -rf .build/tables && cp -R ../../shared/tables .build/tables
swiftc -O -o .build/eval \
  "$APP"/Data/Model/*.swift "$APP"/Data/Local/Entities.swift "$APP"/Data/Local/MenuRecords.swift \
  "$APP"/Data/Local/SQLite.swift "$APP"/Data/Remote/PageTextReader.swift "$APP"/Data/Remote/PageRecipe.swift \
  "$APP"/Data/Remote/JsonLdRecipeParser.swift "$APP"/Data/Remote/MicrodataRecipeParser.swift \
  Sources/*.swift
./.build/eval "$@"
